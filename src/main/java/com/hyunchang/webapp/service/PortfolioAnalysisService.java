package com.hyunchang.webapp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.dto.PortfolioAnalysisResponse;
import com.hyunchang.webapp.dto.PortfolioAnalysisResponse.HoldingAction;
import com.hyunchang.webapp.dto.PortfolioAnalysisResponse.Recommendation;
import com.hyunchang.webapp.dto.StockNewsDto;
import com.hyunchang.webapp.dto.StockPriceDto;
import com.hyunchang.webapp.dto.StockQuoteDto;
import com.hyunchang.webapp.entity.StockHolding;
import com.hyunchang.webapp.service.ai.AiProviderChain;
import com.hyunchang.webapp.util.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 포트폴리오 AI 진단.
 *
 * 흐름:
 *   1. 보유 종목 + 시세 + 평가손익률 조립
 *   2. 시총 Top10 (KR + US) 에서 보유 안 한 종목 추출
 *   3. 시장 뉴스 헤드라인 풀 구성
 *   4. AI provider chain 호출 → JSON 응답 파싱
 *   5. holdings 의 currentPnlPct 는 서버 계산값으로 덮어써서 신뢰도 확보
 *
 * 캐싱 없음 — 매번 최신.
 */
@Service
public class PortfolioAnalysisService {
    private static final Logger log = LoggerFactory.getLogger(PortfolioAnalysisService.class);

    private static final Pattern JSON_FENCE = Pattern.compile("```(?:json)?\\s*(\\{[\\s\\S]*?\\})\\s*```");
    private static final Pattern FIRST_OBJECT = Pattern.compile("\\{[\\s\\S]*\\}");
    private static final Set<String> ALLOWED_ACTIONS = Set.of("TAKE_PROFIT", "HOLD", "CUT_LOSS", "WATCH");

    private final StockHoldingService stockHoldingService;
    private final StockService stockService;
    private final StockSymbolNewsService stockSymbolNewsService;
    private final AiProviderChain aiProviderChain;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PortfolioAnalysisService(StockHoldingService stockHoldingService,
                                    StockService stockService,
                                    StockSymbolNewsService stockSymbolNewsService,
                                    AiProviderChain aiProviderChain) {
        this.stockHoldingService = stockHoldingService;
        this.stockService = stockService;
        this.stockSymbolNewsService = stockSymbolNewsService;
        this.aiProviderChain = aiProviderChain;
    }

    public PortfolioAnalysisResponse analyze() {
        String userId = SecurityUtils.getCurrentUserId();
        List<StockHolding> holdings = stockHoldingService.getHoldings(userId);

        if (holdings.isEmpty()) {
            return PortfolioAnalysisResponse.builder()
                    .blocked(false)
                    .summary("보유 종목이 없습니다. 종목을 먼저 추가해 주세요.")
                    .sentiment("중립")
                    .holdings(List.of())
                    .recommendations(List.of())
                    .disclaimer("이 분석은 AI가 생성한 정보 정리이며 투자 자문이 아닙니다.")
                    .build();
        }

        // 1. 보유 종목 시세 + 평가손익 계산 (서버에서 정확하게)
        List<HoldingSnapshot> snapshots = buildSnapshots(holdings);

        // 1-1. 보유 종목별 뉴스 헤드라인 1~2건씩 병렬 수집
        //      (시세만 주면 "추세 양호" 같은 균일 답변이 나와서 종목별 차별화 컨텍스트 추가)
        Map<String, List<String>> perSymbolNews = fetchPerSymbolNewsParallel(snapshots);

        // 2. 시총 Top10 (KR + US) 중 보유 안 한 종목
        Set<String> heldSymbols = new HashSet<>();
        for (StockHolding h : holdings) heldSymbols.add(h.getSymbol().toUpperCase(Locale.ROOT));
        List<StockQuoteDto> top10Pool = pickTop10Candidates(heldSymbols);

        // 3. 시장 뉴스 헤드라인 (거시 보충용)
        List<String> marketHeadlines = collectMarketHeadlines();

        // 4. 프롬프트 + chain 호출
        String prompt = buildPrompt(snapshots, perSymbolNews, top10Pool, marketHeadlines);
        AiProviderChain.ChainResult chainResult = aiProviderChain.analyze(prompt);

        int perSymbolTotal = perSymbolNews.values().stream().mapToInt(List::size).sum();
        log.info("[Portfolio/Analysis] user={} 보유 {}개, 종목별 뉴스 합 {}건, Top10 후보 {}개, 시장 뉴스 {}건",
                userId, holdings.size(), perSymbolTotal, top10Pool.size(), marketHeadlines.size());

        if (!chainResult.success()) {
            return PortfolioAnalysisResponse.builder()
                    .blocked(true)
                    .retryAt(chainResult.retryAt())
                    .providersStatus(chainResult.providersStatus())
                    .disclaimer("이 분석은 AI가 생성한 정보 정리이며 투자 자문이 아닙니다.")
                    .build();
        }

        // 5. JSON 파싱
        PortfolioAnalysisResponse parsed = parseResult(chainResult.text());

        // 6. 보유 종목 currentPnlPct 는 서버 계산값으로 덮어씀 (LLM 환각 방지)
        if (parsed.getHoldings() != null) {
            for (HoldingAction ha : parsed.getHoldings()) {
                HoldingSnapshot snap = findSnapshot(snapshots, ha.getSymbol());
                if (snap != null) {
                    ha.setCurrentPnlPct(snap.pnlPct);
                    if (ha.getName() == null || ha.getName().isBlank()) ha.setName(snap.name);
                    if (ha.getMarket() == null || ha.getMarket().isBlank()) ha.setMarket(snap.market);
                }
                if (!ALLOWED_ACTIONS.contains(ha.getAction())) {
                    ha.setAction("HOLD");
                }
            }
        }

        return PortfolioAnalysisResponse.builder()
                .blocked(false)
                .providerName(chainResult.providerName())
                .model(chainResult.model())
                .analyzedAt(Instant.now())
                .summary(parsed.getSummary())
                .sentiment(parsed.getSentiment() == null ? "중립" : parsed.getSentiment())
                .holdings(parsed.getHoldings() == null ? List.of() : parsed.getHoldings())
                .recommendations(parsed.getRecommendations() == null ? List.of() : parsed.getRecommendations())
                .disclaimer(parsed.getDisclaimer() == null || parsed.getDisclaimer().isBlank()
                        ? "이 분석은 AI가 생성한 정보 정리이며 투자 자문이 아닙니다."
                        : parsed.getDisclaimer())
                .providersStatus(chainResult.providersStatus())
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────
    // 컨텍스트 수집

    private List<HoldingSnapshot> buildSnapshots(List<StockHolding> holdings) {
        List<HoldingSnapshot> out = new ArrayList<>();
        for (StockHolding h : holdings) {
            HoldingSnapshot s = new HoldingSnapshot();
            s.symbol = h.getSymbol();
            s.name = h.getName();
            s.market = h.getMarket();
            s.avgPrice = h.getAvgPrice();
            try {
                StockPriceDto p = stockService.getQuote(h.getSymbol(), h.getMarket());
                if (p != null) {
                    s.currentPrice = p.getPrice();
                    s.changePercent = p.getChangePercent();
                    s.currency = p.getCurrency();
                }
            } catch (Exception e) {
                log.warn("[Portfolio/Analysis] 시세 조회 실패 {}: {}", h.getSymbol(), e.getMessage());
            }
            if (s.avgPrice != null && s.avgPrice > 0 && s.currentPrice > 0) {
                s.pnlPct = ((s.currentPrice - s.avgPrice) / s.avgPrice) * 100.0;
            }
            out.add(s);
        }
        return out;
    }

    private List<StockQuoteDto> pickTop10Candidates(Set<String> heldSymbols) {
        List<StockQuoteDto> all = new ArrayList<>();
        try { all.addAll(stockService.getTop10KR()); } catch (Exception ignore) {}
        try { all.addAll(stockService.getTop10US()); } catch (Exception ignore) {}
        return all.stream()
                .filter(q -> q != null && q.getSymbol() != null)
                .filter(q -> !heldSymbols.contains(q.getSymbol().toUpperCase(Locale.ROOT)))
                .toList();
    }

    /**
     * 보유 종목 각각에 대해 종목별 뉴스 1~2건씩 병렬 수집.
     * StockSymbolNewsService 가 Google News + Yahoo Finance + AlphaVantage 합산해서 돌려준 결과 중 상위만 채택.
     * 종목별 컨텍스트를 차별화해서 LLM 응답이 "추세 양호" 같은 균일 문구로 수렴하는 것을 방지한다.
     */
    private Map<String, List<String>> fetchPerSymbolNewsParallel(List<HoldingSnapshot> snapshots) {
        List<CompletableFuture<Map.Entry<String, List<String>>>> futures = new ArrayList<>();
        for (HoldingSnapshot s : snapshots) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    String enName = "US".equalsIgnoreCase(s.market)
                            ? stockService.getUsEnNames().get(s.symbol.toUpperCase(Locale.ROOT))
                            : null;
                    List<StockNewsDto> news = stockSymbolNewsService.fetchForSymbol(s.symbol, s.market, s.name, enName);
                    List<String> headlines = new ArrayList<>();
                    for (StockNewsDto n : news) {
                        if (notBlank(n.getTitle())) headlines.add(n.getTitle().trim());
                        if (headlines.size() >= 2) break;
                    }
                    return Map.entry(s.symbol, headlines);
                } catch (Exception e) {
                    log.warn("[Portfolio/Analysis] 종목별 뉴스 fetch 실패 {}: {}", s.symbol, e.getMessage());
                    return Map.entry(s.symbol, List.<String>of());
                }
            }));
        }
        Map<String, List<String>> result = new HashMap<>();
        for (CompletableFuture<Map.Entry<String, List<String>>> f : futures) {
            try {
                Map.Entry<String, List<String>> entry = f.get(8, TimeUnit.SECONDS);
                result.put(entry.getKey(), entry.getValue());
            } catch (Exception e) {
                log.warn("[Portfolio/Analysis] 종목별 뉴스 future 실패: {}", e.getMessage());
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        }
        return result;
    }

    private List<String> collectMarketHeadlines() {
        List<String> headlines = new ArrayList<>();
        try {
            for (StockNewsDto n : stockService.getNews("KR", false)) {
                if (notBlank(n.getTitle())) headlines.add("[KR] " + n.getTitle());
                if (headlines.size() >= 7) break;
            }
        } catch (Exception ignore) {}
        try {
            for (StockNewsDto n : stockService.getNews("US", false)) {
                if (notBlank(n.getTitle())) headlines.add("[US] " + n.getTitle());
                if (headlines.size() >= 14) break;
            }
        } catch (Exception ignore) {}
        return headlines;
    }

    private HoldingSnapshot findSnapshot(List<HoldingSnapshot> snapshots, String symbol) {
        if (symbol == null) return null;
        for (HoldingSnapshot s : snapshots) {
            if (symbol.equalsIgnoreCase(s.symbol)) return s;
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 프롬프트 조립

    private String buildPrompt(List<HoldingSnapshot> snapshots,
                               Map<String, List<String>> perSymbolNews,
                               List<StockQuoteDto> top10Pool,
                               List<String> marketHeadlines) {
        StringBuilder holdingsBlock = new StringBuilder();
        int i = 1;
        for (HoldingSnapshot s : snapshots) {
            holdingsBlock.append(i++).append(". ")
                    .append(s.name).append(" (").append(s.symbol).append(", ").append(s.market).append(")\n")
                    .append("   현재가: ").append(fmtPrice(s)).append(", 평단가: ")
                    .append(s.avgPrice == null ? "미입력" : fmtNum(s.avgPrice, s.market))
                    .append(", 평가손익률: ").append(fmtPnlPct(s.pnlPct))
                    .append(", 일변동률: ").append(fmtPct(s.changePercent)).append("\n");
            // 종목별 뉴스 헤드라인 1~2건 — 각 종목 분석을 차별화하는 결정적 단서
            List<String> hl = perSymbolNews == null ? null : perSymbolNews.get(s.symbol);
            if (hl != null && !hl.isEmpty()) {
                holdingsBlock.append("   관련 뉴스:\n");
                for (String title : hl) {
                    holdingsBlock.append("     - ").append(title).append("\n");
                }
            } else {
                holdingsBlock.append("   관련 뉴스: (수집된 종목별 뉴스 없음)\n");
            }
        }

        StringBuilder top10Block = new StringBuilder();
        if (top10Pool.isEmpty()) {
            top10Block.append("(보유 안 한 시총 Top10 종목 없음)");
        } else {
            int j = 1;
            for (StockQuoteDto q : top10Pool) {
                top10Block.append(j++).append(". ")
                        .append(q.getName()).append(" (").append(q.getSymbol()).append(")")
                        .append(" 시총 ").append(q.getMarketCap())
                        .append(", 일변동률 ").append(fmtPct(q.getChangePercent())).append("\n");
                if (j > 20) break;
            }
        }

        StringBuilder newsBlock = new StringBuilder();
        if (marketHeadlines.isEmpty()) {
            newsBlock.append("(시장 뉴스 없음)");
        } else {
            int k = 1;
            for (String h : marketHeadlines) {
                newsBlock.append(k++).append(". ").append(h).append("\n");
            }
        }

        return """
                당신은 한국 주식 시장 분석가입니다. 아래 사용자의 보유 포트폴리오 정보와 시장 상황을
                근거로 (1) 보유 종목별 시그널과 (2) 추천 종목 2개를 제시하세요.
                추측이나 일반론은 금지. 응답은 반드시 아래 스키마의 JSON 객체 하나로만 출력하세요.
                코드블록·해설·다른 텍스트는 절대 포함하지 마세요.

                ── 보유 종목 ──
                %s
                ── 시총 Top10 풀 (보유 안 한 종목만 — 아래 리스트에서만 TOP10 추천을 골라야 함) ──
                %s
                ── 최근 시장 뉴스 헤드라인 ──
                %s
                ── 작성 지침 ──
                holdings:
                  - 보유 종목 전부에 대해 action 4단계 중 하나를 선택:
                    * TAKE_PROFIT (이익실현 권장: 현재 이익이 크고 추가 상승 여력 제한적)
                    * HOLD (보유 유지: 추세·펀더멘털 양호)
                    * CUT_LOSS (손절 검토: 손실이 크고 회복 가능성 낮음)
                    * WATCH (관망: 판단 보류, 추가 정보 필요)
                  - reason 은 2문장 이내. ★★ 반드시 그 종목 고유의 정보를 직접 인용해야 함:
                    (a) 그 종목의 평가손익률 수치 (예: "+5.13%%") 또는
                    (b) 그 종목의 일변동률 수치 또는
                    (c) 그 종목의 '관련 뉴스' 헤드라인에서 따온 고유 키워드(상품명·계약·실적·이슈 등)
                    셋 중 최소 하나는 reason 본문에 명시적으로 등장해야 함.
                  - ★★ 모든 holdings 의 reason 을 다 작성한 후 서로 비교 검토할 것.
                    동일하거나 의미상 거의 같은 표현(핵심 단어가 똑같거나 한두 글자만 다른 경우)이
                    두 종목 이상에서 발견되면 반드시 처음부터 다시 작성해
                    모든 reason 이 서로 명확히 구별되도록 할 것.
                    각 종목마다 서로 다른 뉴스 헤드라인 또는 서로 다른 수치를 인용해서 차별화.
                  - "추세 양호", "보유 권장", "안정적 성장", "AI 모멘텀", "성장 지속" 같은
                    여러 종목에 두루 통하는 공통 표현은 금지. 같은 의미의 영어 표현도 금지(예: "strong growth").
                  - newsHint 는 그 종목의 '관련 뉴스' 중 가장 임팩트 있는 1건의 헤드라인 그대로
                    (관련 뉴스가 없으면 빈 문자열). 이게 reason 의 핵심 근거여야 함.
                recommendations: 정확히 2개.
                  - 첫 번째 source="TOP10": 위 시총 Top10 풀에 있는 종목 중에서만 1개 선정.
                  - 두 번째 source="FREE": Top10 풀 밖에서 자유 추천 1개. 실재하는 상장 종목이어야 함.
                  - 각 추천에 reason·risks·fitForPortfolio 작성.

                ── 응답 스키마 ──
                {
                  "summary": "포트폴리오 전체 한 줄 평가 (60자 이내)",
                  "sentiment": "긍정" | "중립" | "부정",
                  "holdings": [
                    {
                      "symbol": "...",
                      "name": "...",
                      "market": "KR" | "US",
                      "action": "TAKE_PROFIT" | "HOLD" | "CUT_LOSS" | "WATCH",
                      "reason": "...",
                      "newsHint": "..."
                    }
                  ],
                  "recommendations": [
                    {
                      "source": "TOP10",
                      "symbol": "...",
                      "name": "...",
                      "market": "KR" | "US",
                      "reason": "...",
                      "risks": "...",
                      "fitForPortfolio": "..."
                    },
                    {
                      "source": "FREE",
                      "symbol": "...",
                      "name": "...",
                      "market": "KR" | "US",
                      "reason": "...",
                      "risks": "...",
                      "fitForPortfolio": "..."
                    }
                  ],
                  "disclaimer": "이 분석은 정보 제공 목적이며 투자 자문이 아닙니다."
                }
                """.formatted(holdingsBlock, top10Block, newsBlock);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 응답 파싱

    private PortfolioAnalysisResponse parseResult(String text) {
        String json = extractJson(text);
        try {
            JsonNode root = objectMapper.readTree(json);
            PortfolioAnalysisResponse out = PortfolioAnalysisResponse.builder()
                    .summary(text(root, "summary", ""))
                    .sentiment(text(root, "sentiment", "중립"))
                    .disclaimer(text(root, "disclaimer", null))
                    .build();

            List<HoldingAction> ha = new ArrayList<>();
            for (JsonNode n : root.path("holdings")) {
                ha.add(HoldingAction.builder()
                        .symbol(text(n, "symbol", ""))
                        .name(text(n, "name", ""))
                        .market(text(n, "market", ""))
                        .action(text(n, "action", "HOLD"))
                        .reason(text(n, "reason", ""))
                        .newsHint(text(n, "newsHint", ""))
                        .build());
            }
            out.setHoldings(ha);

            List<Recommendation> recs = new ArrayList<>();
            for (JsonNode n : root.path("recommendations")) {
                recs.add(Recommendation.builder()
                        .source(text(n, "source", "FREE"))
                        .symbol(text(n, "symbol", ""))
                        .name(text(n, "name", ""))
                        .market(text(n, "market", ""))
                        .reason(text(n, "reason", ""))
                        .risks(text(n, "risks", ""))
                        .fitForPortfolio(text(n, "fitForPortfolio", ""))
                        .build());
                if (recs.size() >= 2) break;
            }
            out.setRecommendations(recs);
            return out;
        } catch (Exception e) {
            log.warn("[Portfolio/Analysis] JSON 파싱 실패: {} (text head: {})",
                    e.getMessage(), truncate(text, 200));
            return PortfolioAnalysisResponse.builder()
                    .summary(truncate(text, 200))
                    .sentiment("중립")
                    .holdings(List.of())
                    .recommendations(List.of())
                    .build();
        }
    }

    private String extractJson(String text) {
        if (text == null) return "{}";
        String trimmed = text.trim();
        Matcher m = JSON_FENCE.matcher(trimmed);
        if (m.find()) return m.group(1);
        Matcher m2 = FIRST_OBJECT.matcher(trimmed);
        if (m2.find()) return m2.group();
        return trimmed;
    }

    private String text(JsonNode n, String field, String fallback) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? fallback : v.asText(fallback);
    }

    @SuppressWarnings("unused") // 추후 응답 본문에 List<String> 직접 채우는 경로에서 사용
    private List<String> stringList(JsonNode arr) {
        if (arr == null || !arr.isArray()) return List.of();
        try { return objectMapper.convertValue(arr, new TypeReference<>() {}); }
        catch (Exception e) {
            List<String> out = new ArrayList<>();
            arr.forEach(x -> out.add(x.asText()));
            return Collections.unmodifiableList(out);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 포맷터

    private String fmtPrice(HoldingSnapshot s) {
        if (s.currentPrice <= 0) return "조회실패";
        return fmtNum(s.currentPrice, s.market) + (notBlank(s.currency) ? " " + s.currency : "");
    }
    private String fmtNum(double v, String market) {
        if ("KR".equalsIgnoreCase(market)) return String.format(Locale.KOREA, "%,d", (long) v);
        return String.format(Locale.US, "%,.2f", v);
    }
    private String fmtPct(double v) { return String.format(Locale.US, "%+.2f%%", v); }
    private String fmtPnlPct(Double v) { return v == null ? "미입력" : String.format(Locale.US, "%+.2f%%", v); }

    private boolean notBlank(String s) { return s != null && !s.isBlank(); }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // 보유 종목 컨텍스트 임시 객체
    private static class HoldingSnapshot {
        String symbol;
        String name;
        String market;
        Double avgPrice;
        double currentPrice;
        double changePercent;
        String currency;
        Double pnlPct;
    }
}
