package com.hyunchang.webapp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.dto.StockAnalysisResponse;
import com.hyunchang.webapp.dto.StockAnalysisResult;
import com.hyunchang.webapp.dto.StockNewsDto;
import com.hyunchang.webapp.dto.StockPriceDto;
import com.hyunchang.webapp.service.ai.AiProviderChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
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
    private final AiProviderChain aiProviderChain;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public StockAnalysisService(StockService stockService, AiProviderChain aiProviderChain) {
        this.stockService = stockService;
        this.aiProviderChain = aiProviderChain;
    }

    public StockAnalysisResponse analyze(String symbol, String market) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol 이 비어있습니다.");
        }
        String mkt = (market == null || market.isBlank()) ? "KR" : market.toUpperCase(Locale.ROOT);

        // 1. 컨텍스트 수집
        String name = safe(() -> stockService.resolveStockName(symbol), symbol);
        StockPriceDto price = safe(() -> stockService.getQuote(symbol, mkt), null);
        List<StockNewsDto> news = safe(() -> stockService.getNews(mkt, false), List.<StockNewsDto>of());
        List<StockNewsDto> topNews = news == null
                ? List.of()
                : news.stream().limit(10).toList();

        // 2. 프롬프트
        String prompt = buildPrompt(name, symbol, mkt, price, topNews);

        // 3. chain 호출
        AiProviderChain.ChainResult chainResult = aiProviderChain.analyze(prompt);

        if (!chainResult.success()) {
            return StockAnalysisResponse.builder()
                    .blocked(true)
                    .retryAt(chainResult.retryAt())
                    .providersStatus(chainResult.providersStatus())
                    .build();
        }

        // 4. JSON 파싱
        StockAnalysisResult parsed = parseResult(chainResult.text());

        // 5. 응답 조립 (참고 뉴스는 상위 5개만)
        List<StockNewsDto> sources = topNews.stream().limit(5).toList();

        return StockAnalysisResponse.builder()
                .blocked(false)
                .providerName(chainResult.providerName())
                .model(chainResult.model())
                .analyzedAt(Instant.now())
                .result(parsed)
                .sources(sources)
                .providersStatus(chainResult.providersStatus())
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────
    // 프롬프트 조립

    private String buildPrompt(String name, String symbol, String market,
                               StockPriceDto price, List<StockNewsDto> news) {
        StringBuilder priceBlock = new StringBuilder();
        if (price != null) {
            priceBlock.append("현재가: ").append(formatPrice(price)).append(" ").append(nullSafe(price.getCurrency())).append("\n");
            priceBlock.append("등락률: ").append(formatChangePct(price)).append("%\n");
        } else {
            priceBlock.append("(시세 조회 실패)\n");
        }

        StringBuilder newsBlock = new StringBuilder();
        if (news.isEmpty()) {
            newsBlock.append("(최근 시장 뉴스 없음)");
        } else {
            int i = 1;
            for (StockNewsDto n : news) {
                newsBlock.append(i++).append(". ")
                        .append(nullSafe(n.getTitle()))
                        .append(" — ")
                        .append(truncate(nullSafe(n.getDescription()), 200))
                        .append("\n");
            }
        }

        return """
                당신은 한국 주식 시장 분석가입니다. 아래 종목 정보와 최근 시장 뉴스를 보고
                해당 종목에 관련된 내용 위주로 간결하게 분석하세요.
                응답은 반드시 아래 스키마의 JSON 객체 하나로만 출력하세요.
                코드블록·해설·다른 텍스트를 절대 포함하지 마세요.

                ── 종목 ──
                이름: %s
                티커: %s
                시장: %s
                %s
                ── 최근 시장 뉴스 (관련된 내용만 인용) ──
                %s
                ── 응답 스키마 ──
                {
                  "sentiment": "긍정" | "중립" | "부정",
                  "headline": "한 줄 핵심 요약 (60자 이내, 한국어)",
                  "keywords": ["키워드1", "키워드2", "키워드3"],
                  "positives": ["호재 1", "호재 2"],
                  "risks": ["리스크 1", "리스크 2"],
                  "comment": "2~3문장 종합 코멘트 (투자 자문이 아닌 정보 정리 톤)"
                }
                """.formatted(
                        nullSafe(name),
                        nullSafe(symbol),
                        nullSafe(market),
                        priceBlock.toString(),
                        newsBlock.toString());
    }

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

    /** 외부 호출 실패해도 분석은 계속 진행할 수 있게 fallback 으로 감싼다. */
    private <T> T safe(java.util.function.Supplier<T> sup, T fallback) {
        try { return sup.get(); } catch (Exception e) {
            log.warn("[AI/Analysis] 컨텍스트 수집 부분 실패: {}", e.getMessage());
            return fallback;
        }
    }
}
