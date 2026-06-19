package com.hyunchang.webapp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.dto.StockAnalysisResponse;
import com.hyunchang.webapp.dto.StockAnalysisResult;
import com.hyunchang.webapp.dto.StockNewsDto;
import com.hyunchang.webapp.dto.StockPriceDto;
import com.hyunchang.webapp.service.ai.AiProviderChain;
import com.hyunchang.webapp.service.prompt.AiPromptCatalog;
import com.hyunchang.webapp.service.prompt.AiPromptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 종목 분석 오케스트레이션.
 *
 * 흐름:
 *   1. 종목명·시세·시장 뉴스를 모아서
 *   2. 프롬프트 조립 (한국어, JSON 응답 강제)
 *   3. AiProviderChain 으로 호출 (Gemini → Groq → Cloudflare)
 *   4. JSON 파싱 (모델별로 응답이 markdown 코드블록에 감싸여 올 수 있어 정리)
 *   5. 결과 + 참고 뉴스 + provider 메타데이터 반환
 *
 * 캐싱 없음 — 누를 때마다 최신 데이터로 새 분석.
 */
@Service
public class StockAnalysisService {
    private static final Logger log = LoggerFactory.getLogger(StockAnalysisService.class);

    // Markdown 코드블록 안에 JSON 이 감싸여 오는 경우 (Cloudflare 등) 추출용
    private static final Pattern JSON_FENCE = Pattern.compile("```(?:json)?\\s*(\\{[\\s\\S]*?\\})\\s*```");
    private static final Pattern FIRST_OBJECT = Pattern.compile("\\{[\\s\\S]*\\}");

    private final StockService stockService;
    private final StockSymbolNewsService stockSymbolNewsService;
    private final AiProviderChain aiProviderChain;
    private final AiPromptService aiPromptService;
    private final FinancialDataService financialDataService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public StockAnalysisService(StockService stockService,
                                StockSymbolNewsService stockSymbolNewsService,
                                AiProviderChain aiProviderChain,
                                AiPromptService aiPromptService,
                                FinancialDataService financialDataService) {
        this.stockService = stockService;
        this.stockSymbolNewsService = stockSymbolNewsService;
        this.aiProviderChain = aiProviderChain;
        this.aiPromptService = aiPromptService;
        this.financialDataService = financialDataService;
    }

    public StockAnalysisResponse analyze(String symbol, String market) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol 이 비어있습니다.");
        }
        String mkt = (market == null || market.isBlank()) ? "KR" : market.toUpperCase(Locale.ROOT);

        // 1. 컨텍스트 수집
        String name = safe(() -> stockService.resolveStockName(symbol), symbol);
        StockPriceDto price = safe(() -> stockService.getQuote(symbol, mkt), null);
        String enName = "US".equalsIgnoreCase(mkt)
                ? safe(() -> stockService.getUsEnNames().get(symbol.toUpperCase(Locale.ROOT)), null)
                : null;

        // 1-1. 종목별 직접 검색 뉴스 (KR: Google News / US: Yahoo Finance + AlphaVantage + Google News)
        List<StockNewsDto> symbolNews = safe(
                () -> stockSymbolNewsService.fetchForSymbol(symbol, mkt, name, enName),
                List.<StockNewsDto>of());

        // 1-2. 시장 단위 뉴스에서 종목 관련 항목만 필터링 (보조 — 거시 뉴스 보충용)
        List<StockNewsDto> allMarketNews = safe(() -> stockService.getNews(mkt, false), List.<StockNewsDto>of());
        List<StockNewsDto> marketFiltered = allMarketNews == null
                ? List.<StockNewsDto>of()
                : allMarketNews.stream()
                        .filter(n -> isRelevant(n, name, symbol, enName))
                        .toList();

        // 1-3. 종목별 뉴스 우선 + 시장 필터링 보충, URL/제목 기준 중복 제거 후 상위 10건
        List<StockNewsDto> relatedNews = mergeNews(symbolNews, marketFiltered, 10);

        log.info("[AI/Analysis] 종목 {} ({}) 뉴스 수집: 종목별 {} + 시장필터링 {} → 합산 {} 건",
                symbol, name,
                symbolNews == null ? 0 : symbolNews.size(),
                marketFiltered.size(),
                relatedNews.size());

        // 1-4. 재무 데이터 (US: Yahoo, KR: 추후 DART) — 실패해도 분석 진행
        String financials = safe(() -> financialDataService.stockSummary(symbol, mkt), "(재무 데이터 미수집)");

        // 2. 프롬프트
        String prompt = buildPrompt(name, symbol, mkt, price, relatedNews, financials);

        // 3. chain 호출
        AiProviderChain.ChainResult chainResult = aiProviderChain.analyze(prompt);

        if (!chainResult.success()) {
            return StockAnalysisResponse.builder()
                    .blocked(true)
                    .retryAt(chainResult.retryAt())
                    .providersStatus(chainResult.providersStatus())
                    .build();
        }

        // 4. 마크다운 리포트 (자유리포트 — JSON 파싱 없이 AI 원문 사용)
        String report = cleanReport(chainResult.text());

        // 5. 응답 조립 (참고 뉴스는 종목 관련 뉴스 상위 5개)
        List<StockNewsDto> sources = relatedNews.stream().limit(5).toList();

        return StockAnalysisResponse.builder()
                .blocked(false)
                .providerName(chainResult.providerName())
                .model(chainResult.model())
                .analyzedAt(Instant.now())
                .report(report)
                .sources(sources)
                .providersStatus(chainResult.providersStatus())
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────
    // 프롬프트 조립

    private String buildPrompt(String name, String symbol, String market,
                               StockPriceDto price, List<StockNewsDto> news, String financials) {
        StringBuilder priceBlock = new StringBuilder();
        if (price != null) {
            priceBlock.append("현재가: ").append(formatPrice(price)).append(" ").append(nullSafe(price.getCurrency())).append("\n");
            priceBlock.append("등락률: ").append(formatChangePct(price)).append("%\n");
        } else {
            priceBlock.append("(시세 조회 실패)\n");
        }

        StringBuilder newsBlock = new StringBuilder();
        String newsInstruction;
        if (news.isEmpty()) {
            newsBlock.append("(이 종목에 관한 최근 뉴스가 수집되지 않았습니다.)");
            newsInstruction = """
                    이 종목에 대한 최근 뉴스를 찾지 못했습니다. 시세 정보만으로 신중하게 분석하고,
                    정보가 부족하면 'comment' 필드에 "최근 관련 뉴스가 부족해 분석 신뢰도가 낮습니다"
                    라는 식으로 명시해 주세요. 추측이나 일반적인 시장 코멘트로 빈 자리를 채우지 마세요.
                    """;
        } else {
            int i = 1;
            for (StockNewsDto n : news) {
                newsBlock.append(i++).append(". ")
                        .append(nullSafe(n.getTitle()))
                        .append(" — ")
                        .append(truncate(nullSafe(n.getDescription()), 200))
                        .append("\n");
            }
            newsInstruction = """
                    아래 뉴스는 이 종목과 관련해 수집된 참고 자료입니다.
                    뉴스를 분석에 얼마나 반영할지는 위 지침을 따르세요.
                    단, 구체적 사실을 인용할 때는 아래 뉴스 범위 안에서만 하고, 뉴스에 없는 사실을 지어내지 마세요.
                    """;
        }

        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("종목명", nullSafe(name));
        vars.put("티커", nullSafe(symbol));
        vars.put("시장", nullSafe(market));
        vars.put("시세정보", priceBlock.toString());
        vars.put("재무데이터", financials == null || financials.isBlank() ? "(재무 데이터 미수집)" : financials);
        vars.put("뉴스지침", newsInstruction.strip());
        vars.put("뉴스목록", newsBlock.toString());
        return aiPromptService.render(AiPromptCatalog.STOCK_ANALYSIS, vars)
                + "\n\n" + stockAnalysisUiSchema();
    }

    private String stockAnalysisUiSchema() {
        return """

                --- UI 출력 강제 규칙 ---
                위 지침보다 아래 출력 규칙을 우선합니다.
                응답은 반드시 순수 JSON 객체 하나만 출력하세요. 마크다운, 코드블록, 표 문법, 설명 문장을 JSON 밖에 쓰지 마세요.
                데이터가 부족한 항목은 추측하지 말고 "데이터 없음" 또는 "확인 필요"로 표기하세요.

                {
                  "stock_analysis": {
                    "executive_summary": {
                      "investment_score": 0,
                      "investment_opinion": "매수 | 중립 | 매도",
                      "summary": "핵심 결론을 2~3문장으로 요약",
                      "key_points": [
                        "API/뉴스/시세 데이터 기반 핵심 근거 1",
                        "API/뉴스/시세 데이터 기반 핵심 근거 2",
                        "API/뉴스/시세 데이터 기반 핵심 근거 3"
                      ]
                    },
                    "metrics": [
                      {
                        "metric": "현재가",
                        "value": "숫자와 단위",
                        "source": "KRX | OpenDART | Yahoo | Alpha Vantage | 앱 시세",
                        "comment": "해당 지표가 투자 판단에 주는 의미"
                      },
                      {
                        "metric": "PER",
                        "value": "숫자 또는 데이터 없음",
                        "source": "OpenDART | Yahoo | 데이터 없음",
                        "comment": "업종/이익 흐름 대비 해석"
                      },
                      {
                        "metric": "PBR",
                        "value": "숫자 또는 데이터 없음",
                        "source": "OpenDART | Yahoo | 데이터 없음",
                        "comment": "자산가치 대비 해석"
                      },
                      {
                        "metric": "ROE/수익성",
                        "value": "숫자 또는 데이터 없음",
                        "source": "OpenDART | Yahoo | 데이터 없음",
                        "comment": "수익성에 대한 판단"
                      }
                    ],
                    "macro_industry_analysis": "금리, 환율, 경기, 업종 수급이 이 기업에 미치는 영향을 2~3문장으로 작성",
                    "financial_health": "재무 건전성, 현금흐름, 수익성에 대한 판단을 작성. 데이터가 부족하면 제한점을 명시",
                    "valuation_analysis": "현재 가격이 저평가/적정/고평가인지 판단하고 근거를 작성. 추정 수치를 만들지 말 것",
                    "risk_scenarios": [
                      {
                        "scenario_name": "리스크 시나리오 1",
                        "impact": "이 시나리오가 종목에 주는 영향",
                        "response": "대응 전략"
                      },
                      {
                        "scenario_name": "리스크 시나리오 2",
                        "impact": "이 시나리오가 종목에 주는 영향",
                        "response": "대응 전략"
                      }
                    ],
                    "data_limitations": "이번 분석에서 부족하거나 확인이 필요한 데이터",
                    "api_data_as_of": "분석 기준일 또는 확인 가능한 최신 기준일",
                    "critical_question": "사용자의 투자 논리를 검증할 날카로운 질문 1개"
                  }
                }
                """;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 뉴스 합산: 종목별 직접 검색을 우선 채택하고, 시장 필터링 결과를 보충으로 덧붙인다.
    // dedupe: link 또는 제목 기준 (어느 쪽이 우선 들어왔든 한 번만)

    private List<StockNewsDto> mergeNews(List<StockNewsDto> primary, List<StockNewsDto> secondary, int limit) {
        java.util.LinkedHashMap<String, StockNewsDto> seen = new java.util.LinkedHashMap<>();
        if (primary != null) {
            for (StockNewsDto n : primary) {
                String key = dedupeKey(n);
                if (!key.isBlank()) seen.putIfAbsent(key, n);
            }
        }
        if (secondary != null) {
            for (StockNewsDto n : secondary) {
                String key = dedupeKey(n);
                if (!key.isBlank()) seen.putIfAbsent(key, n);
            }
        }
        return seen.values().stream().limit(limit).toList();
    }

    private String dedupeKey(StockNewsDto n) {
        if (n == null) return "";
        if (notBlank(n.getLink())) return n.getLink().trim().toLowerCase(Locale.ROOT);
        if (notBlank(n.getTitle())) return n.getTitle().trim().toLowerCase(Locale.ROOT);
        return "";
    }

    // ─────────────────────────────────────────────────────────────────────
    // 종목별 뉴스 필터링 (시장 단위 RSS 보충용)
    // 한국 종목은 한글명·6자리 코드, 미국 종목은 ticker·영문명 + 원문 영어 텍스트도 검사.

    private boolean isRelevant(StockNewsDto n, String name, String symbol, String enName) {
        if (n == null) return false;
        String haystack = combinedText(n).toLowerCase(Locale.ROOT);
        if (haystack.isBlank()) return false;

        // 1) 한글 종목명 직접 매칭 (2글자 이상이면 충분히 변별력 있음)
        if (notBlank(name) && name.length() >= 2 && haystack.contains(name.toLowerCase(Locale.ROOT))) {
            return true;
        }
        // 2) 티커 코드 매칭 — .KS/.KQ 접미사 제거 후 3글자 이상만 (오탐 방지)
        if (notBlank(symbol)) {
            String code = symbol.split("\\.")[0].toLowerCase(Locale.ROOT);
            if (code.length() >= 3 && haystack.contains(code)) return true;
        }
        // 3) 영문 회사명 매칭 (US 종목)
        if (notBlank(enName) && enName.length() >= 3 && haystack.contains(enName.toLowerCase(Locale.ROOT))) {
            return true;
        }
        return false;
    }

    private String combinedText(StockNewsDto n) {
        return String.join(" ",
                nullSafe(n.getTitle()),
                nullSafe(n.getDescription()),
                nullSafe(n.getOriginalTitle()),
                nullSafe(n.getOriginalDescription()));
    }

    private boolean notBlank(String s) { return s != null && !s.isBlank(); }

    // ─────────────────────────────────────────────────────────────────────
    // JSON 파싱 (모델별 응답 차이 흡수)

    private StockAnalysisResult parseResult(String text) {
        String json = extractJson(text);
        try {
            JsonNode root = objectMapper.readTree(json);
            return StockAnalysisResult.builder()
                    .sentiment(text(root, "sentiment", "중립"))
                    .headline(text(root, "headline", ""))
                    .keywords(stringList(root.path("keywords")))
                    .positives(stringList(root.path("positives")))
                    .risks(stringList(root.path("risks")))
                    .comment(text(root, "comment", ""))
                    .build();
        } catch (Exception e) {
            log.warn("[AI/Analysis] JSON 파싱 실패: {} (text head: {})",
                    e.getMessage(), truncate(text, 200));
            // 파싱 실패해도 raw 텍스트를 코멘트로 노출해 사용자가 볼 수 있게
            return StockAnalysisResult.builder()
                    .sentiment("중립")
                    .headline("")
                    .keywords(List.of())
                    .positives(List.of())
                    .risks(List.of())
                    .comment(truncate(text, 500))
                    .build();
        }
    }

    private String extractJson(String text) {
        if (text == null) return "{}";
        String trimmed = text.trim();
        // 1순위: ```json ... ``` 코드블록 안
        Matcher m = JSON_FENCE.matcher(trimmed);
        if (m.find()) return m.group(1);
        // 2순위: 첫 { ... } 블록
        Matcher m2 = FIRST_OBJECT.matcher(trimmed);
        if (m2.find()) return m2.group();
        return trimmed;
    }

    private String text(JsonNode node, String field, String fallback) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? fallback : v.asText(fallback);
    }

    private List<String> stringList(JsonNode arr) {
        if (arr == null || !arr.isArray()) return List.of();
        try {
            return objectMapper.convertValue(arr, new TypeReference<>() {});
        } catch (Exception e) {
            List<String> out = new ArrayList<>();
            arr.forEach(n -> out.add(n.asText()));
            return Collections.unmodifiableList(out);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 포맷터/유틸

    private String formatPrice(StockPriceDto p) {
        double price = p.getPrice();
        if ("KR".equalsIgnoreCase(market(p.getCurrency()))) {
            return String.format(Locale.KOREA, "%,d", (long) price);
        }
        return String.format(Locale.US, "%,.2f", price);
    }

    private String formatChangePct(StockPriceDto p) {
        return String.format(Locale.US, "%+.2f", p.getChangePercent());
    }

    private String market(String currency) {
        return "KRW".equalsIgnoreCase(currency) ? "KR" : "US";
    }

    private String nullSafe(String s) { return s == null ? "" : s; }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** AI 마크다운 리포트 정리 — 전체를 감싼 ``` 코드펜스가 있으면 벗긴다. */
    private String cleanReport(String text) {
        if (text == null) return "";
        String t = text.strip();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) t = t.substring(nl + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
            t = t.strip();
        }
        return t;
    }

    /** 외부 호출 실패해도 분석은 계속 진행할 수 있게 fallback 으로 감싼다. */
    private <T> T safe(java.util.function.Supplier<T> sup, T fallback) {
        try { return sup.get(); } catch (Exception e) {
            log.warn("[AI/Analysis] 컨텍스트 수집 부분 실패: {}", e.getMessage());
            return fallback;
        }
    }
}
