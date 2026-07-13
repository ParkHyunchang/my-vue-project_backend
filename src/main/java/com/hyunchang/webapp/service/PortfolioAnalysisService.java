package com.hyunchang.webapp.service;

import com.hyunchang.webapp.dto.GeneralHoldingResponse;
import com.hyunchang.webapp.dto.PortfolioAnalysisResponse;
import com.hyunchang.webapp.dto.StockNewsDto;
import com.hyunchang.webapp.dto.StockPriceDto;
import com.hyunchang.webapp.entity.IrpHolding;
import com.hyunchang.webapp.entity.IsaHolding;
import com.hyunchang.webapp.entity.StockHolding;
import com.hyunchang.webapp.service.ai.AiProviderChain;
import com.hyunchang.webapp.service.news.NewsPromptFormatter;
import com.hyunchang.webapp.service.portfolio.PortfolioAccountType;
import com.hyunchang.webapp.service.prompt.AiPromptCatalog;
import com.hyunchang.webapp.service.prompt.AiPromptService;
import com.hyunchang.webapp.util.SecurityUtils;
import com.hyunchang.webapp.util.Texts;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 포트폴리오 AI 진단.
 *
 * <p>흐름: 1. 보유 종목 + 시세 + 평가손익률 조립 2. 보유 종목별 뉴스 + 최근 시장 뉴스 헤드라인 풀 구성 3. 재무 데이터(US: Yahoo, KR:
 * OpenDART) 수집 4. AI provider chain 호출 → 마크다운 리포트 반환
 *
 * <p>캐싱 없음 — 매번 최신.
 */
@Service
public class PortfolioAnalysisService {
    private static final Logger log = LoggerFactory.getLogger(PortfolioAnalysisService.class);

    private static final String ASSET_CASH = "CASH";
    private static final String ASSET_STOCK = "STOCK";
    private static final double IRP_RISKY_ASSET_LIMIT_PCT = 70.0;
    private static final double IRP_SAFE_ASSET_TARGET_PCT = 30.0;
    private static final LocalDate ISA_OPENED_ON = LocalDate.of(2026, 6, 24);

    // 종목별 뉴스 병렬 수집 전용 풀 — 공용 ForkJoinPool은 NAS(저코어)에서 병렬도가 급감함
    private static final ExecutorService NEWS_POOL = Executors.newFixedThreadPool(8);
    // 종목별 뉴스 수집 전체 시간 예산 — 초과 시 남은 종목은 뉴스 없이 분석 진행
    private static final long PER_SYMBOL_NEWS_BUDGET_MS = 25_000L;

    private final StockHoldingService stockHoldingService;
    private final IsaHoldingService isaHoldingService;
    private final GeneralHoldingService generalHoldingService;
    private final IrpHoldingService irpHoldingService;
    private final StockService stockService;
    private final StockSymbolNewsService stockSymbolNewsService;
    private final AiProviderChain aiProviderChain;
    private final AiPromptService aiPromptService;
    private final FinancialDataService financialDataService;
    private final ShortSwingCandidateService shortSwingCandidateService;

    public PortfolioAnalysisService(
            StockHoldingService stockHoldingService,
            IsaHoldingService isaHoldingService,
            GeneralHoldingService generalHoldingService,
            IrpHoldingService irpHoldingService,
            StockService stockService,
            StockSymbolNewsService stockSymbolNewsService,
            AiProviderChain aiProviderChain,
            AiPromptService aiPromptService,
            FinancialDataService financialDataService,
            ShortSwingCandidateService shortSwingCandidateService) {
        this.stockHoldingService = stockHoldingService;
        this.isaHoldingService = isaHoldingService;
        this.generalHoldingService = generalHoldingService;
        this.irpHoldingService = irpHoldingService;
        this.stockService = stockService;
        this.stockSymbolNewsService = stockSymbolNewsService;
        this.aiProviderChain = aiProviderChain;
        this.aiPromptService = aiPromptService;
        this.financialDataService = financialDataService;
        this.shortSwingCandidateService = shortSwingCandidateService;
    }

    @PreDestroy
    void shutdownNewsPool() {
        NEWS_POOL.shutdown();
    }

    public PortfolioAnalysisResponse analyze() {
        return analyze(null);
    }

    public PortfolioAnalysisResponse analyze(Map<String, Object> requestBody) {
        String userId = SecurityUtils.getCurrentUserId();
        AnalysisAccount account = parseAnalysisAccount(requestBody);
        List<PortfolioHoldingInput> holdings = loadHoldings(userId, account);

        // 마켓 필터 적용 (프론트 선택에 따라 KR/US만 진단)
        String marketFilter = parseMarketFilter(requestBody);
        if ("KR".equals(marketFilter)) {
            holdings = holdings.stream().filter(h -> "KR".equals(h.market)).toList();
        } else if ("US".equals(marketFilter)) {
            holdings = holdings.stream().filter(h -> "US".equals(h.market)).toList();
        }

        if (holdings.isEmpty()) {
            return PortfolioAnalysisResponse.builder()
                    .blocked(false)
                    .summary(account.label + " 보유 자산이 없습니다. 종목이나 현금성 자산을 먼저 추가해 주세요.")
                    .sentiment("중립")
                    .disclaimer("이 분석은 AI가 생성한 정보 정리이며 투자 자문이 아닙니다.")
                    .build();
        }

        // 1. 보유 종목 시세 + 평가손익 계산 (서버에서 정확하게)
        Map<String, ClientHoldingContext> clientContext = parseClientContext(requestBody);
        List<HoldingSnapshot> snapshots = buildSnapshots(holdings, clientContext);

        // 1-1. 보유 종목별 뉴스 헤드라인 1~2건씩 병렬 수집
        //      (시세만 주면 "추세 양호" 같은 균일 답변이 나와서 종목별 차별화 컨텍스트 추가)
        Map<String, List<String>> perSymbolNews = fetchPerSymbolNewsParallel(snapshots);

        // 2. 추천에서 제외할 보유 종목 (이미 가진 종목은 추천하지 않음)
        List<String> heldLabels = new ArrayList<>();
        for (PortfolioHoldingInput h : holdings) heldLabels.add(h.name + " (" + h.symbol + ")");

        // 3. 최근 시장 뉴스 헤드라인 — 추천의 핵심 근거
        List<String> marketHeadlines = collectMarketHeadlines();

        // 3-1. 보유 종목 재무 데이터 (US: Yahoo, KR: 추후 DART) — 실패해도 분석 진행
        String financials;
        try {
            List<FinancialDataService.Holding> finHoldings =
                    snapshots.stream()
                            .filter(s -> !s.isCash())
                            .map(s -> new FinancialDataService.Holding(s.symbol, s.name, s.market))
                            .toList();
            financials =
                    finHoldings.isEmpty()
                            ? "(주식/ETF 보유분이 없어 재무 데이터 수집 대상 없음)"
                            : financialDataService.holdingsSummary(finHoldings);
        } catch (Exception e) {
            log.warn("[Portfolio/Analysis] 재무 데이터 수집 실패: {}", e.getMessage());
            financials = "(재무 데이터 미수집)";
        }

        // 4. 프롬프트 + chain 호출 (자유리포트 마크다운 — API 레벨 JSON 강제 없이 요청)
        //    컴팩트 버전은 Groq 등 입력 한도가 작은 provider 폴백용 — 종합(ALL) 프롬프트는 무료
        //    TPM 한도를 넘어 413 으로 거부되므로, 근거를 줄인 축약본으로라도 응답을 받는다.
        String prompt =
                buildPrompt(
                        account,
                        snapshots,
                        perSymbolNews,
                        marketHeadlines,
                        heldLabels,
                        financials,
                        false);
        String compactPrompt =
                buildPrompt(
                        account,
                        snapshots,
                        perSymbolNews,
                        marketHeadlines,
                        heldLabels,
                        financials,
                        true);
        AiProviderChain.ChainResult chainResult =
                aiProviderChain.analyze(prompt, compactPrompt, false);

        int perSymbolTotal = perSymbolNews.values().stream().mapToInt(List::size).sum();
        long weightCount = snapshots.stream().filter(s -> s.weightPct != null).count();
        log.info(
                "[Portfolio/Analysis] user={} account={} 보유 {}개, 비중 {}개, 종목별 뉴스 합 {}건, 시장 뉴스 {}건",
                userId,
                account.type.code(),
                holdings.size(),
                weightCount,
                perSymbolTotal,
                marketHeadlines.size());

        if (!chainResult.success()) {
            return PortfolioAnalysisResponse.builder()
                    .blocked(true)
                    .retryAt(chainResult.retryAt())
                    .providersStatus(chainResult.providersStatus())
                    .disclaimer("이 분석은 AI가 생성한 정보 정리이며 투자 자문이 아닙니다.")
                    .build();
        }

        // 5. 마크다운 리포트 (자유리포트 — JSON 파싱/오버레이 없이 AI 원문 사용)
        //    보유 종목 손익률 등 정확한 수치는 이미 프롬프트({{보유종목}})에 서버 계산값으로 들어가므로 리포트도 정확.
        String report = Texts.cleanAiReport(chainResult.text());

        return PortfolioAnalysisResponse.builder()
                .blocked(false)
                .providerName(chainResult.providerName())
                .model(chainResult.model())
                .analyzedAt(Instant.now())
                .report(report)
                .disclaimer("이 분석은 AI가 생성한 정보 정리이며 투자 자문이 아닙니다.")
                .providersStatus(chainResult.providersStatus())
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────
    // 컨텍스트 수집

    private AnalysisAccount parseAnalysisAccount(Map<String, Object> requestBody) {
        Object rawType = requestBody == null ? null : requestBody.get("accountType");
        Object rawLabel = requestBody == null ? null : requestBody.get("accountLabel");
        Object rawNote = requestBody == null ? null : requestBody.get("accountNote");

        if (requestBody != null && requestBody.get("portfolio") instanceof Map<?, ?> portfolio) {
            if (rawType == null) rawType = portfolio.get("accountType");
            if (rawLabel == null) rawLabel = portfolio.get("accountLabel");
            if (rawNote == null) rawNote = portfolio.get("accountNote");
        }

        PortfolioAccountType type = PortfolioAccountType.parse(str(rawType));
        String label = notBlank(str(rawLabel)) ? str(rawLabel).trim() : type.defaultLabel();
        return new AnalysisAccount(type, label, str(rawNote));
    }

    private List<PortfolioHoldingInput> loadHoldings(String userId, AnalysisAccount account) {
        return switch (account.type) {
            case ALL -> {
                List<PortfolioHoldingInput> all = new ArrayList<>();
                stockHoldingService.getHoldings(userId).stream()
                        .map(this::fromStockHolding)
                        .forEach(
                                h -> {
                                    h.accountLabel = "장기 주식계좌";
                                    all.add(h);
                                });
                generalHoldingService.getHoldings(userId).stream()
                        .map(this::fromGeneralHolding)
                        .forEach(
                                h -> {
                                    h.accountLabel = "단기 주식계좌";
                                    all.add(h);
                                });
                isaHoldingService.getHoldings(userId).stream()
                        .map(this::fromIsaHolding)
                        .forEach(
                                h -> {
                                    h.accountLabel = "ISA 계좌";
                                    all.add(h);
                                });
                irpHoldingService.getHoldings(userId).stream()
                        .map(this::fromIrpHolding)
                        .forEach(
                                h -> {
                                    h.accountLabel = "IRP 계좌";
                                    all.add(h);
                                });
                yield all;
            }
            case ISA ->
                    isaHoldingService.getHoldings(userId).stream()
                            .map(this::fromIsaHolding)
                            .toList();
            case GENERAL ->
                    generalHoldingService.getHoldings(userId).stream()
                            .map(this::fromGeneralHolding)
                            .toList();
            case IRP ->
                    irpHoldingService.getHoldings(userId).stream()
                            .map(this::fromIrpHolding)
                            .toList();
            case STOCK ->
                    stockHoldingService.getHoldings(userId).stream()
                            .map(this::fromStockHolding)
                            .toList();
        };
    }

    private PortfolioHoldingInput fromStockHolding(StockHolding h) {
        return mapHolding(
                h.getSymbol(),
                h.getName(),
                h.getMarket(),
                h.getQuantity(),
                h.getAvgPrice(),
                h.isCore(),
                ASSET_STOCK);
    }

    private PortfolioHoldingInput fromIsaHolding(IsaHolding h) {
        return mapHolding(
                h.getSymbol(),
                h.getName(),
                h.getMarket(),
                h.getQuantity(),
                h.getAvgPrice(),
                h.isCore(),
                h.getAssetType());
    }

    private PortfolioHoldingInput fromIrpHolding(IrpHolding h) {
        return mapHolding(
                h.getSymbol(),
                h.getName(),
                h.getMarket(),
                h.getQuantity(),
                h.getAvgPrice(),
                h.isCore(),
                h.getAssetType());
    }

    private PortfolioHoldingInput fromGeneralHolding(GeneralHoldingResponse h) {
        return mapHolding(
                h.getSymbol(),
                h.getName(),
                h.getMarket(),
                h.getQuantity(),
                h.getAvgPrice(),
                h.isCore(),
                h.getAssetType());
    }

    private PortfolioHoldingInput mapHolding(
            String symbol,
            String name,
            String market,
            Long quantity,
            Double avgPrice,
            boolean core,
            String assetType) {
        PortfolioHoldingInput out = new PortfolioHoldingInput();
        out.symbol = symbol;
        out.name = name;
        out.market = market;
        out.quantity = quantity;
        out.avgPrice = avgPrice;
        out.core = core;
        out.assetType = normalizeAssetType(assetType);
        return out;
    }

    private String normalizeAssetType(String assetType) {
        return ASSET_CASH.equalsIgnoreCase(assetType) ? ASSET_CASH : ASSET_STOCK;
    }

    private Map<String, ClientHoldingContext> parseClientContext(Map<String, Object> requestBody) {
        Object rawHoldings = requestBody == null ? null : requestBody.get("holdings");
        if (!(rawHoldings instanceof List<?>)
                && requestBody != null
                && requestBody.get("portfolio") instanceof Map<?, ?> portfolio) {
            rawHoldings = portfolio.get("holdings");
        }
        if (!(rawHoldings instanceof List<?> list)) {
            return Map.of();
        }

        Map<String, ClientHoldingContext> out = new HashMap<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            String symbol = str(m.get("symbol"));
            if (!notBlank(symbol)) continue;

            ClientHoldingContext c = new ClientHoldingContext();
            c.weightPct = dbl(m.get("weightPct"));
            if (c.weightPct == null) c.weightPct = dbl(m.get("chartWeightPct"));
            c.marketValue = dbl(m.get("marketValue"));
            c.marketValueKRW = dbl(m.get("marketValueKRW"));
            c.currentPrice = dbl(m.get("currentPrice"));
            out.put(symbol.toUpperCase(Locale.ROOT), c);
        }
        return out;
    }

    private String parseMarketFilter(Map<String, Object> requestBody) {
        Object raw = requestBody == null ? null : requestBody.get("marketFilter");
        if (raw == null
                && requestBody != null
                && requestBody.get("portfolio") instanceof Map<?, ?> p) {
            raw = p.get("marketFilter");
        }
        String val = str(raw);
        if ("KR".equalsIgnoreCase(val)) return "KR";
        if ("US".equalsIgnoreCase(val)) return "US";
        return null;
    }

    private List<HoldingSnapshot> buildSnapshots(
            List<PortfolioHoldingInput> holdings, Map<String, ClientHoldingContext> clientContext) {
        List<HoldingSnapshot> out = new ArrayList<>();
        for (PortfolioHoldingInput h : holdings) {
            HoldingSnapshot s = new HoldingSnapshot();
            s.symbol = h.symbol;
            s.name = h.name;
            s.market = h.market;
            s.quantity = h.quantity;
            s.avgPrice = h.avgPrice;
            s.core = h.core;
            s.assetType = normalizeAssetType(h.assetType);
            s.accountLabel = h.accountLabel;

            if (s.isCash()) {
                s.currentPrice = 1.0;
                s.changePercent = 0.0;
                s.currency = "KRW";
                if (s.quantity != null && s.quantity > 0) {
                    s.marketValue = s.quantity.doubleValue();
                    s.marketValueKRW = s.quantity.doubleValue();
                }
            } else {
                try {
                    StockPriceDto p = stockService.getQuote(h.symbol, h.market);
                    if (p != null) {
                        s.currentPrice = p.getPrice();
                        s.changePercent = p.getChangePercent();
                        s.currency = p.getCurrency();
                    }
                } catch (Exception e) {
                    log.warn("[Portfolio/Analysis] 시세 조회 실패 {}: {}", h.symbol, e.getMessage());
                }
            }
            ClientHoldingContext c =
                    clientContext == null
                            ? null
                            : clientContext.get(h.symbol.toUpperCase(Locale.ROOT));
            if (s.currentPrice <= 0 && c != null && c.currentPrice != null && c.currentPrice > 0) {
                s.currentPrice = c.currentPrice;
            }
            if (s.currentPrice > 0 && s.quantity != null && s.quantity > 0) {
                s.marketValue = s.currentPrice * s.quantity;
                if ("KR".equalsIgnoreCase(s.market)) {
                    s.marketValueKRW = s.marketValue;
                }
            }
            if (c != null) {
                s.weightPct = c.weightPct;
                if (c.marketValue != null) s.marketValue = c.marketValue;
                if (c.marketValueKRW != null) s.marketValueKRW = c.marketValueKRW;
            }
            if (!s.isCash() && s.avgPrice != null && s.avgPrice > 0 && s.currentPrice > 0) {
                s.pnlPct = ((s.currentPrice - s.avgPrice) / s.avgPrice) * 100.0;
            }
            out.add(s);
        }
        fillMissingWeights(out);
        return out;
    }

    private void fillMissingWeights(List<HoldingSnapshot> snapshots) {
        boolean hasAllWeights = snapshots.stream().allMatch(s -> s.weightPct != null);
        if (hasAllWeights) return;

        double totalKRW =
                snapshots.stream()
                        .map(s -> s.marketValueKRW)
                        .filter(v -> v != null && v > 0)
                        .mapToDouble(Double::doubleValue)
                        .sum();
        if (totalKRW <= 0) return;

        for (HoldingSnapshot s : snapshots) {
            if (s.weightPct == null && s.marketValueKRW != null && s.marketValueKRW > 0) {
                s.weightPct = (s.marketValueKRW / totalKRW) * 100.0;
            }
        }
    }

    /**
     * 보유 종목 각각에 대해 종목별 뉴스 5건까지 병렬 수집. StockSymbolNewsService 가 Google News + Yahoo Finance +
     * AlphaVantage 합산해서 돌려준 결과 중 상위만 채택. 종목별 컨텍스트를 차별화해서 LLM 응답이 "추세 양호" 같은 균일 문구로 수렴하는 것을 방지한다.
     *
     * <p>공용 ForkJoinPool 은 NAS 처럼 코어가 적은 환경에서 병렬도가 1~3으로 떨어져 종합(4계좌) 분석이 리버스 프록시 타임아웃(60s)을 넘길 수
     * 있으므로 전용 풀을 사용하고, 수집 전체에 시간 예산을 걸어 초과분은 뉴스 없이 분석을 계속 진행한다.
     */
    private Map<String, List<String>> fetchPerSymbolNewsParallel(List<HoldingSnapshot> snapshots) {
        List<CompletableFuture<Map.Entry<String, List<String>>>> futures = new ArrayList<>();
        Map<String, List<String>> result = new HashMap<>();
        for (HoldingSnapshot s : snapshots) {
            if (s.isCash()) {
                result.put(s.symbol, List.of());
                continue;
            }
            futures.add(
                    CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    String enName =
                                            "US".equalsIgnoreCase(s.market)
                                                    ? stockService
                                                            .getUsEnNames()
                                                            .get(s.symbol.toUpperCase(Locale.ROOT))
                                                    : null;
                                    List<StockNewsDto> news =
                                            stockSymbolNewsService.fetchForSymbol(
                                                    s.symbol, s.market, s.name, enName);
                                    List<String> headlines = new ArrayList<>();
                                    for (StockNewsDto n : news) {
                                        String formatted = NewsPromptFormatter.format(n, 180);
                                        if (notBlank(formatted)) headlines.add(formatted);
                                        if (headlines.size() >= 5) break;
                                    }
                                    return Map.entry(s.symbol, headlines);
                                } catch (Exception e) {
                                    log.warn(
                                            "[Portfolio/Analysis] 종목별 뉴스 fetch 실패 {}: {}",
                                            s.symbol,
                                            e.getMessage());
                                    return Map.entry(s.symbol, List.<String>of());
                                }
                            },
                            NEWS_POOL));
        }
        long deadline = System.currentTimeMillis() + PER_SYMBOL_NEWS_BUDGET_MS;
        for (CompletableFuture<Map.Entry<String, List<String>>> f : futures) {
            try {
                long remaining = Math.max(1, deadline - System.currentTimeMillis());
                Map.Entry<String, List<String>> entry = f.get(remaining, TimeUnit.MILLISECONDS);
                result.put(entry.getKey(), entry.getValue());
            } catch (TimeoutException e) {
                f.cancel(true);
                log.warn("[Portfolio/Analysis] 종목별 뉴스 수집 시간 예산 초과 — 남은 종목은 뉴스 없이 진행");
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
                String formatted = NewsPromptFormatter.format(n, 140);
                if (notBlank(formatted)) headlines.add("[KR] " + formatted);
                if (headlines.size() >= 12) break;
            }
        } catch (Exception ignore) {
        }
        try {
            for (StockNewsDto n : stockService.getNews("US", false)) {
                String formatted = NewsPromptFormatter.format(n, 140);
                if (notBlank(formatted)) headlines.add("[US] " + formatted);
                if (headlines.size() >= 24) break;
            }
        } catch (Exception ignore) {
        }
        return headlines;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 프롬프트 조립

    // 컴팩트 프롬프트 축약 폭 — Groq 무료 TPM(12k 토큰) 아래로 들어가는 것이 목표
    private static final int COMPACT_NEWS_PER_SYMBOL = 1;
    private static final int COMPACT_NEWS_CHARS = 110;
    private static final int COMPACT_MARKET_NEWS = 8;
    private static final int COMPACT_KR_CANDIDATES = 5;
    private static final int COMPACT_US_CANDIDATES = 3;

    /**
     * compact=true 면 입력 한도가 작은 provider 폴백용 축약본을 만든다: 종목별 뉴스 1건(110자), 시장 뉴스 8건, 재무 데이터 생략, 단기 후보
     * 요약(상세 근거 라인 생략). 보유 종목의 수치(수량·비중·손익률 등)는 그대로 유지한다.
     */
    private String buildPrompt(
            AnalysisAccount account,
            List<HoldingSnapshot> snapshots,
            Map<String, List<String>> perSymbolNews,
            List<String> marketHeadlines,
            List<String> heldLabels,
            String financials,
            boolean compact) {
        StringBuilder holdingsBlock = new StringBuilder();
        int i = 1;
        for (HoldingSnapshot s : snapshots) {
            holdingsBlock
                    .append(i++)
                    .append(". ")
                    .append(s.name)
                    .append(" (")
                    .append(s.symbol)
                    .append(", ")
                    .append(s.market)
                    .append(") ");
            if (notBlank(s.accountLabel)) {
                holdingsBlock.append("— [").append(s.accountLabel).append("] ");
            }
            holdingsBlock.append("\n");

            if (s.isCash()) {
                holdingsBlock
                        .append("   자산유형: 현금성 자산(CASH)\n")
                        .append("   금액: ")
                        .append(
                                s.quantity == null
                                        ? "미입력"
                                        : String.format(Locale.KOREA, "%,d KRW", s.quantity))
                        .append(", 현재 비중: ")
                        .append(fmtWeightPct(s.weightPct))
                        .append("\n")
                        .append("   평가금액: ")
                        .append(fmtKRWValue(s.marketValueKRW))
                        .append("\n")
                        .append("   관련 뉴스: (현금성 자산 — 뉴스/시세 조회 대상 아님)\n");
                continue;
            }

            holdingsBlock
                    .append("   자산유형: 주식/ETF(STOCK), 사용자 지정 핵심자산: ")
                    .append(s.core ? "예" : "아니오")
                    .append("\n")
                    .append("   보유수량: ")
                    .append(
                            s.quantity == null
                                    ? "미입력"
                                    : String.format(Locale.KOREA, "%,d", s.quantity))
                    .append("주")
                    .append(", 현재 비중: ")
                    .append(fmtWeightPct(s.weightPct))
                    .append("\n")
                    .append("   현재가: ")
                    .append(fmtPrice(s))
                    .append(", 평단가: ")
                    .append(s.avgPrice == null ? "미입력" : fmtNum(s.avgPrice, s.market))
                    .append(", 평가손익률: ")
                    .append(fmtPnlPct(s.pnlPct))
                    .append(", 일변동률: ")
                    .append(fmtPct(s.changePercent))
                    .append("\n")
                    .append("   평가금액: ")
                    .append(fmtMoney(s.marketValue, s.market))
                    .append(", 원화 평가금액: ")
                    .append(fmtKRWValue(s.marketValueKRW))
                    .append("\n");
            // 종목별 뉴스 헤드라인 1~2건 — 각 종목 분석을 차별화하는 결정적 단서
            List<String> hl = perSymbolNews == null ? null : perSymbolNews.get(s.symbol);
            if (compact && hl != null && hl.size() > COMPACT_NEWS_PER_SYMBOL) {
                hl = hl.subList(0, COMPACT_NEWS_PER_SYMBOL);
            }
            if (hl != null && !hl.isEmpty()) {
                holdingsBlock.append("   관련 뉴스:\n");
                for (String title : hl) {
                    holdingsBlock
                            .append("     - ")
                            .append(compact ? shorten(title, COMPACT_NEWS_CHARS) : title)
                            .append("\n");
                }
            } else {
                holdingsBlock.append("   관련 뉴스: (수집된 종목별 뉴스 없음)\n");
            }
        }

        StringBuilder heldBlock = new StringBuilder();
        if (heldLabels.isEmpty()) {
            heldBlock.append("(없음)");
        } else {
            heldBlock.append(String.join(", ", heldLabels));
        }

        List<String> headlines = marketHeadlines;
        if (compact && headlines.size() > COMPACT_MARKET_NEWS) {
            headlines = headlines.subList(0, COMPACT_MARKET_NEWS);
        }
        StringBuilder newsBlock = new StringBuilder();
        if (headlines.isEmpty()) {
            newsBlock.append("(시장 뉴스 없음)");
        } else {
            int k = 1;
            for (String h : headlines) {
                newsBlock.append(k++).append(". ").append(h).append("\n");
            }
        }

        String financialsBlock =
                financials == null || financials.isBlank() ? "(재무 데이터 미수집)" : financials;
        if (compact) {
            financialsBlock = "(간이 분석 모드 — 재무 데이터 생략, 시세·비중·손익률·뉴스 기반으로 분석하세요)";
        }

        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("계좌유형", account.label);
        vars.put("계좌설명", notBlank(account.note) ? account.note : account.type.defaultNote());
        vars.put("보유종목", holdingsBlock.toString());
        vars.put("재무데이터", financialsBlock);
        vars.put("시장뉴스", newsBlock.toString());
        vars.put("보유종목목록", heldBlock.toString());
        vars.put("계좌비중점검", accountAllocationMemo(account, snapshots));
        String candidateData = buildShortRecommendationData(account, snapshots, compact);
        return aiPromptService.render(account.type.promptKey(), vars)
                + "\n\n"
                + additionalAccountInstruction(account, snapshots, candidateData);
    }

    private String shorten(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "…";
    }

    /** compact=true 면 후보 수를 줄이고(KR 5·US 3) 상세 근거 라인(공시·뉴스·컨센서스)을 생략한다. */
    private String buildShortRecommendationData(
            AnalysisAccount account, List<HoldingSnapshot> snapshots, boolean compact) {
        if (account.type != PortfolioAccountType.GENERAL
                && account.type != PortfolioAccountType.ALL) {
            return "";
        }
        StringBuilder out = new StringBuilder("=== 단기 종목 추가 후보 데이터 (출처별 사실만 사용) ===\n");
        out.append("[KRX 스크리닝 + DART 공시 + 종목 뉴스]\n");
        shortSwingCandidateService.getKrCandidatesWithCatalysts(12).stream()
                .filter(
                        c ->
                                snapshots.stream()
                                        .noneMatch(
                                                h ->
                                                        h.symbol.equalsIgnoreCase(
                                                                c.candidate().symbol())))
                .limit(compact ? COMPACT_KR_CANDIDATES : Long.MAX_VALUE)
                .forEach(
                        c -> {
                            var q = c.candidate();
                            out.append("- ")
                                    .append(q.name())
                                    .append(" (")
                                    .append(q.symbol())
                                    .append(")")
                                    .append(": KRX ")
                                    .append(q.market())
                                    .append(", 등락률 ")
                                    .append(fmtPct(q.changePercent()))
                                    .append(", 거래량 ")
                                    .append(q.volume())
                                    .append(", 20일 평균 대비 ")
                                    .append(q.volumeRatio())
                                    .append("배\n");
                            if (compact) return;
                            c.disclosures()
                                    .forEach(
                                            d ->
                                                    out.append("  - DART 공시: ")
                                                            .append(d.date())
                                                            .append(" ")
                                                            .append(d.title())
                                                            .append("\n"));
                            c.news()
                                    .forEach(
                                            n ->
                                                    out.append("  - 종목 뉴스: ")
                                                            .append(n.title())
                                                            .append(" (출처: ")
                                                            .append(n.source())
                                                            .append(")\n"));
                        });
        out.append("[US Alpha Vantage 감성 + Yahoo 컨센서스]\n");
        shortSwingCandidateService.getUsCandidatesWithSignals(12).stream()
                .filter(
                        c ->
                                snapshots.stream()
                                        .noneMatch(
                                                h ->
                                                        h.symbol.equalsIgnoreCase(
                                                                c.candidate().symbol())))
                .limit(compact ? COMPACT_US_CANDIDATES : Long.MAX_VALUE)
                .forEach(
                        c -> {
                            var q = c.candidate();
                            out.append("- ")
                                    .append(q.name())
                                    .append(" (")
                                    .append(q.symbol())
                                    .append(")")
                                    .append(": Yahoo 등락률 ")
                                    .append(fmtPct(q.changePercent()))
                                    .append(", Alpha Vantage 평균 감성 ")
                                    .append(String.format(Locale.US, "%.2f", c.averageSentiment()))
                                    .append("\n");
                            if (compact) return;
                            out.append("  - Yahoo 컨센서스: ").append(c.consensus()).append("\n");
                            c.positiveSentimentNews()
                                    .forEach(
                                            n ->
                                                    out.append("  - Alpha Vantage 감성 뉴스: ")
                                                            .append(n.getTitle())
                                                            .append(" (감성 ")
                                                            .append(n.getSentimentScore())
                                                            .append(", 관련도 ")
                                                            .append(n.getRelevanceScore())
                                                            .append(")\n"));
                        });
        out.append("후보가 없으면 데이터 부족으로 간주하고 추천 후보 없음으로 출력하세요.");
        return out.toString();
    }

    private String additionalAccountInstruction(
            AnalysisAccount account, List<HoldingSnapshot> snapshots, String candidateData) {
        String accountInstruction;
        if (account.type == PortfolioAccountType.ISA) {
            accountInstruction =
                    account.type.additionalInstruction()
                            + isaTaxMemo()
                            + "\n"
                            + accountAllocationMemo(account, snapshots);
        } else {
            accountInstruction =
                    account.type.additionalInstruction()
                            + accountAllocationMemo(account, snapshots);
        }

        // 단기 계좌의 신규 편입 후보는 별도 관리 프롬프트로 조합한다. 종합 진단에도
        // 같은 섹션을 포함해 사용자가 단기 계좌에 추가할 후보를 한 번에 확인할 수 있다.
        if (account.type == PortfolioAccountType.GENERAL
                || account.type == PortfolioAccountType.ALL) {
            return accountInstruction
                    + "\n\n"
                    + candidateData
                    + "\n\n"
                    + aiPromptService.instruction(AiPromptCatalog.SHORT_STOCK_RECOMMENDATION);
        }
        return accountInstruction;
    }

    private String isaTaxMemo() {
        LocalDate mandatoryEndOn = ISA_OPENED_ON.plusYears(3);
        return "ISA 기본 배경정보(내부 판단용): 2026-06-24 신규 개설한 서민형 ISA, 의무가입기간 3년, 의무가입기간 충족 기준일 "
                + mandatoryEndOn
                + ", 서민형 비과세 한도 400만원. "
                + "이 정보는 항상 출력하지 말고, 매도/인출/세제 유의점 판단에 직접 관련될 때만 사용하세요.";
    }

    private String accountAllocationMemo(AnalysisAccount account, List<HoldingSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return "계좌 비중 점검: 보유 자산이 없어 위험자산/안전자산 비중을 계산할 수 없습니다.";
        }

        double riskyPct =
                snapshots.stream()
                        .filter(s -> !s.isCash())
                        .map(s -> s.weightPct)
                        .filter(v -> v != null && v > 0)
                        .mapToDouble(Double::doubleValue)
                        .sum();
        double cashPct =
                snapshots.stream()
                        .filter(HoldingSnapshot::isCash)
                        .map(s -> s.weightPct)
                        .filter(v -> v != null && v > 0)
                        .mapToDouble(Double::doubleValue)
                        .sum();
        double knownPct = riskyPct + cashPct;

        if (knownPct <= 0) {
            return "계좌 비중 점검: 현재가 또는 평가금액이 부족해 위험자산/안전자산 비중을 계산할 수 없습니다.";
        }

        String base =
                String.format(
                        Locale.KOREA,
                        "계좌 비중 점검: 주식/ETF 등 위험자산 %.1f%%, CASH 기준 안전/현금성 자산 %.1f%%입니다. ",
                        riskyPct,
                        cashPct);

        if (account.type == PortfolioAccountType.IRP) {
            double remainingRiskCapacity = Math.max(0, IRP_RISKY_ASSET_LIMIT_PCT - riskyPct);
            double safeGap = Math.max(0, IRP_SAFE_ASSET_TARGET_PCT - cashPct);
            String rule =
                    String.format(
                            Locale.KOREA,
                            "IRP 기본 배경정보(내부 판단용): 위험자산 한도 70%% 기준상 위험자산 추가 가능 여력 약 %.1f%%p, 안전자산 30%% 기준 대비 부족분 약 %.1f%%p. ",
                            remainingRiskCapacity,
                            safeGap);
            String status =
                    riskyPct > IRP_RISKY_ASSET_LIMIT_PCT
                            ? "현재 위험자산 비중이 70%를 초과하므로 위험자산 추가보다 위험자산 축소 또는 안전자산 보강을 우선하는 판단 근거로만 사용하세요. "
                            : safeGap > 0
                                    ? "위험자산 추가 매수 전 안전/현금성 자산 보강 필요 여부를 판단하는 내부 기준으로만 사용하세요. "
                                    : "위험자산 한도 안에서 추가 매수 여부를 판단하되, 은퇴자산 변동성 관리를 우선하는 내부 기준으로 사용하세요. ";
            return base
                    + rule
                    + status
                    + "필요하지 않으면 출력에서 IRP 한도 규칙을 반복하지 마세요. 실제 퇴직연금 상품별 위험/안전자산 분류는 사업자 기준과 상품 약관을 확인해야 하며, 이 서비스는 CASH를 안전/현금성 자산으로 계산합니다.";
        }

        if (account.type == PortfolioAccountType.ISA) {
            return base
                    + "ISA 기본정보는 세제·의무기간 판단이 필요할 때만 출력에 반영하고, 평소에는 현금성 자산을 리밸런싱 재원으로 쓸 수 있는지 중심으로 판단하세요. "
                    + isaTaxMemo();
        }

        return base;
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

    private String fmtPct(double v) {
        return String.format(Locale.US, "%+.2f%%", v);
    }

    private String fmtPnlPct(Double v) {
        return v == null ? "미입력" : String.format(Locale.US, "%+.2f%%", v);
    }

    private String fmtWeightPct(Double v) {
        return v == null ? "미입력" : String.format(Locale.US, "%.2f%%", v);
    }

    private String fmtMoney(Double v, String market) {
        if (v == null || v <= 0) return "미입력";
        return fmtNum(v, market) + ("KR".equalsIgnoreCase(market) ? " KRW" : " USD");
    }

    private String fmtKRWValue(Double v) {
        if (v == null || v <= 0) return "미입력";
        return String.format(Locale.KOREA, "%,d KRW", Math.round(v));
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private Double dbl(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        try {
            String s = String.valueOf(v).replaceAll("[^0-9.+-]", "");
            if (s.isBlank()) return null;
            return Double.parseDouble(s);
        } catch (Exception e) {
            return null;
        }
    }

    // 보유 종목 컨텍스트 임시 객체
    private static class AnalysisAccount {
        final PortfolioAccountType type;
        final String label;
        final String note;

        AnalysisAccount(PortfolioAccountType type, String label, String note) {
            this.type = type;
            this.label = label;
            this.note = note;
        }
    }

    private static class PortfolioHoldingInput {
        String symbol;
        String name;
        String market;
        String assetType;
        boolean core;
        Long quantity;
        Double avgPrice;
        String accountLabel;
    }

    private static class HoldingSnapshot {
        String symbol;
        String name;
        String market;
        String assetType;
        boolean core;
        Long quantity;
        Double avgPrice;
        double currentPrice;
        double changePercent;
        String currency;
        Double pnlPct;
        Double marketValue;
        Double marketValueKRW;
        Double weightPct;
        String accountLabel;

        boolean isCash() {
            return ASSET_CASH.equalsIgnoreCase(assetType);
        }
    }

    private static class ClientHoldingContext {
        Double currentPrice;
        Double marketValue;
        Double marketValueKRW;
        Double weightPct;
    }
}
