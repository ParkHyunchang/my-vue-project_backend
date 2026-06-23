package com.hyunchang.webapp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.dto.PortfolioAnalysisResponse;
import com.hyunchang.webapp.dto.PortfolioAnalysisResponse.GradeItem;
import com.hyunchang.webapp.dto.PortfolioAnalysisResponse.Grades;
import com.hyunchang.webapp.dto.PortfolioAnalysisResponse.HoldingAction;
import com.hyunchang.webapp.dto.PortfolioAnalysisResponse.KeyHolding;
import com.hyunchang.webapp.dto.PortfolioAnalysisResponse.Recommendation;
import com.hyunchang.webapp.dto.PortfolioAnalysisResponse.Scenario;
import com.hyunchang.webapp.dto.StockNewsDto;
import com.hyunchang.webapp.dto.StockPriceDto;
import com.hyunchang.webapp.entity.IrpHolding;
import com.hyunchang.webapp.entity.IsaHolding;
import com.hyunchang.webapp.entity.StockHolding;
import com.hyunchang.webapp.service.ai.AiProviderChain;
import com.hyunchang.webapp.service.prompt.AiPromptCatalog;
import com.hyunchang.webapp.service.prompt.AiPromptService;
import com.hyunchang.webapp.util.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
 *   2. 보유 종목별 뉴스 + 최근 시장 뉴스 헤드라인 풀 구성
 *   3. 최근 뉴스 기반 추천 종목 2~3개를 AI 가 선정 (보유 종목은 추천 제외)
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
    private static final Set<String> ALLOWED_ACTIONS = Set.of("ADD", "TAKE_PROFIT", "HOLD", "CUT_LOSS", "WATCH");
    private static final String ACCOUNT_STOCK = "stock";
    private static final String ACCOUNT_ISA = "isa";
    private static final String ACCOUNT_IRP = "irp";
    private static final String ASSET_CASH = "CASH";
    private static final String ASSET_STOCK = "STOCK";

    private final StockHoldingService stockHoldingService;
    private final IsaHoldingService isaHoldingService;
    private final IrpHoldingService irpHoldingService;
    private final StockService stockService;
    private final StockSymbolNewsService stockSymbolNewsService;
    private final AiProviderChain aiProviderChain;
    private final AiPromptService aiPromptService;
    private final FinancialDataService financialDataService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PortfolioAnalysisService(StockHoldingService stockHoldingService,
                                    IsaHoldingService isaHoldingService,
                                    IrpHoldingService irpHoldingService,
                                    StockService stockService,
                                    StockSymbolNewsService stockSymbolNewsService,
                                    AiProviderChain aiProviderChain,
                                    AiPromptService aiPromptService,
                                    FinancialDataService financialDataService) {
        this.stockHoldingService = stockHoldingService;
        this.isaHoldingService = isaHoldingService;
        this.irpHoldingService = irpHoldingService;
        this.stockService = stockService;
        this.stockSymbolNewsService = stockSymbolNewsService;
        this.aiProviderChain = aiProviderChain;
        this.aiPromptService = aiPromptService;
        this.financialDataService = financialDataService;
    }

    public PortfolioAnalysisResponse analyze() {
        return analyze(null);
    }

    public PortfolioAnalysisResponse analyze(Map<String, Object> requestBody) {
        String userId = SecurityUtils.getCurrentUserId();
        AnalysisAccount account = parseAnalysisAccount(requestBody);
        List<PortfolioHoldingInput> holdings = loadHoldings(userId, account);

        if (holdings.isEmpty()) {
            return PortfolioAnalysisResponse.builder()
                    .blocked(false)
                    .summary(account.label + " 보유 자산이 없습니다. 종목이나 현금성 자산을 먼저 추가해 주세요.")
                    .sentiment("중립")
                    .holdings(List.of())
                    .recommendations(List.of())
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
            List<FinancialDataService.Holding> finHoldings = snapshots.stream()
                    .filter(s -> !s.isCash())
                    .map(s -> new FinancialDataService.Holding(s.symbol, s.name, s.market))
                    .toList();
            financials = finHoldings.isEmpty()
                    ? "(주식/ETF 보유분이 없어 재무 데이터 수집 대상 없음)"
                    : financialDataService.holdingsSummary(finHoldings);
        } catch (Exception e) {
            log.warn("[Portfolio/Analysis] 재무 데이터 수집 실패: {}", e.getMessage());
            financials = "(재무 데이터 미수집)";
        }

        // 4. 프롬프트 + chain 호출
        String prompt = buildPrompt(account, snapshots, perSymbolNews, marketHeadlines, heldLabels, financials);
        AiProviderChain.ChainResult chainResult = aiProviderChain.analyze(prompt);

        int perSymbolTotal = perSymbolNews.values().stream().mapToInt(List::size).sum();
        long weightCount = snapshots.stream().filter(s -> s.weightPct != null).count();
        log.info("[Portfolio/Analysis] user={} account={} 보유 {}개, 비중 {}개, 종목별 뉴스 합 {}건, 시장 뉴스 {}건",
                userId, account.type, holdings.size(), weightCount, perSymbolTotal, marketHeadlines.size());

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
        String report = cleanReport(chainResult.text());

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

        String type = str(rawType);
        type = type == null ? ACCOUNT_STOCK : type.trim().toLowerCase(Locale.ROOT);

        return switch (type) {
            case ACCOUNT_ISA -> new AnalysisAccount(
                    ACCOUNT_ISA,
                    notBlank(str(rawLabel)) ? str(rawLabel).trim() : "ISA",
                    AiPromptCatalog.PORTFOLIO_ISA_ANALYSIS,
                    str(rawNote)
            );
            case ACCOUNT_IRP -> new AnalysisAccount(
                    ACCOUNT_IRP,
                    notBlank(str(rawLabel)) ? str(rawLabel).trim() : "퇴직연금 IRP",
                    AiPromptCatalog.PORTFOLIO_IRP_ANALYSIS,
                    str(rawNote)
            );
            default -> new AnalysisAccount(
                    ACCOUNT_STOCK,
                    notBlank(str(rawLabel)) ? str(rawLabel).trim() : "주식",
                    AiPromptCatalog.PORTFOLIO_ANALYSIS,
                    str(rawNote)
            );
        };
    }

    private List<PortfolioHoldingInput> loadHoldings(String userId, AnalysisAccount account) {
        if (ACCOUNT_ISA.equals(account.type)) {
            return isaHoldingService.getHoldings(userId).stream()
                    .map(this::fromIsaHolding)
                    .toList();
        }
        if (ACCOUNT_IRP.equals(account.type)) {
            return irpHoldingService.getHoldings(userId).stream()
                    .map(this::fromIrpHolding)
                    .toList();
        }
        return stockHoldingService.getHoldings(userId).stream()
                .map(this::fromStockHolding)
                .toList();
    }

    private PortfolioHoldingInput fromStockHolding(StockHolding h) {
        PortfolioHoldingInput out = new PortfolioHoldingInput();
        out.symbol = h.getSymbol();
        out.name = h.getName();
        out.market = h.getMarket();
        out.quantity = h.getQuantity();
        out.avgPrice = h.getAvgPrice();
        out.core = h.isCore();
        out.assetType = ASSET_STOCK;
        return out;
    }

    private PortfolioHoldingInput fromIsaHolding(IsaHolding h) {
        PortfolioHoldingInput out = new PortfolioHoldingInput();
        out.symbol = h.getSymbol();
        out.name = h.getName();
        out.market = h.getMarket();
        out.quantity = h.getQuantity();
        out.avgPrice = h.getAvgPrice();
        out.core = h.isCore();
        out.assetType = normalizeAssetType(h.getAssetType());
        return out;
    }

    private PortfolioHoldingInput fromIrpHolding(IrpHolding h) {
        PortfolioHoldingInput out = new PortfolioHoldingInput();
        out.symbol = h.getSymbol();
        out.name = h.getName();
        out.market = h.getMarket();
        out.quantity = h.getQuantity();
        out.avgPrice = h.getAvgPrice();
        out.core = h.isCore();
        out.assetType = normalizeAssetType(h.getAssetType());
        return out;
    }

    private String normalizeAssetType(String assetType) {
        return ASSET_CASH.equalsIgnoreCase(assetType) ? ASSET_CASH : ASSET_STOCK;
    }

    private Map<String, ClientHoldingContext> parseClientContext(Map<String, Object> requestBody) {
        Object rawHoldings = requestBody == null ? null : requestBody.get("holdings");
        if (!(rawHoldings instanceof List<?> list) && requestBody != null && requestBody.get("portfolio") instanceof Map<?, ?> portfolio) {
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

    private List<HoldingSnapshot> buildSnapshots(List<PortfolioHoldingInput> holdings,
                                                 Map<String, ClientHoldingContext> clientContext) {
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
            ClientHoldingContext c = clientContext == null
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

        double totalKRW = snapshots.stream()
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
     * 보유 종목 각각에 대해 종목별 뉴스 1~2건씩 병렬 수집.
     * StockSymbolNewsService 가 Google News + Yahoo Finance + AlphaVantage 합산해서 돌려준 결과 중 상위만 채택.
     * 종목별 컨텍스트를 차별화해서 LLM 응답이 "추세 양호" 같은 균일 문구로 수렴하는 것을 방지한다.
     */
    private Map<String, List<String>> fetchPerSymbolNewsParallel(List<HoldingSnapshot> snapshots) {
        List<CompletableFuture<Map.Entry<String, List<String>>>> futures = new ArrayList<>();
        Map<String, List<String>> result = new HashMap<>();
        for (HoldingSnapshot s : snapshots) {
            if (s.isCash()) {
                result.put(s.symbol, List.of());
                continue;
            }
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
                if (headlines.size() >= 10) break;
            }
        } catch (Exception ignore) {}
        try {
            for (StockNewsDto n : stockService.getNews("US", false)) {
                if (notBlank(n.getTitle())) headlines.add("[US] " + n.getTitle());
                if (headlines.size() >= 20) break;
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

    private String buildPrompt(AnalysisAccount account,
                               List<HoldingSnapshot> snapshots,
                               Map<String, List<String>> perSymbolNews,
                               List<String> marketHeadlines,
                               List<String> heldLabels,
                               String financials) {
        StringBuilder holdingsBlock = new StringBuilder();
        int i = 1;
        for (HoldingSnapshot s : snapshots) {
            holdingsBlock.append(i++).append(". ")
                    .append(s.name).append(" (").append(s.symbol).append(", ").append(s.market).append(") ")
                    .append("\n");

            if (s.isCash()) {
                holdingsBlock
                    .append("   자산유형: 현금성 자산(CASH)\n")
                    .append("   금액: ").append(s.quantity == null ? "미입력" : String.format(Locale.KOREA, "%,d KRW", s.quantity))
                    .append(", 현재 비중: ").append(fmtWeightPct(s.weightPct)).append("\n")
                    .append("   평가금액: ").append(fmtKRWValue(s.marketValueKRW)).append("\n")
                    .append("   관련 뉴스: (현금성 자산 — 뉴스/시세 조회 대상 아님)\n");
                continue;
            }

            holdingsBlock
                    .append("   자산유형: 주식/ETF(STOCK), 사용자 지정 핵심자산: ").append(s.core ? "예" : "아니오").append("\n")
                    .append("   보유수량: ").append(s.quantity == null ? "미입력" : String.format(Locale.KOREA, "%,d", s.quantity)).append("주")
                    .append(", 현재 비중: ").append(fmtWeightPct(s.weightPct)).append("\n")
                    .append("   현재가: ").append(fmtPrice(s)).append(", 평단가: ")
                    .append(s.avgPrice == null ? "미입력" : fmtNum(s.avgPrice, s.market))
                    .append(", 평가손익률: ").append(fmtPnlPct(s.pnlPct))
                    .append(", 일변동률: ").append(fmtPct(s.changePercent)).append("\n")
                    .append("   평가금액: ").append(fmtMoney(s.marketValue, s.market))
                    .append(", 원화 평가금액: ").append(fmtKRWValue(s.marketValueKRW)).append("\n");
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

        StringBuilder heldBlock = new StringBuilder();
        if (heldLabels.isEmpty()) {
            heldBlock.append("(없음)");
        } else {
            heldBlock.append(String.join(", ", heldLabels));
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

        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("계좌유형", account.label);
        vars.put("계좌설명", notBlank(account.note) ? account.note : defaultAccountNote(account.type));
        vars.put("보유종목", holdingsBlock.toString());
        vars.put("재무데이터", financials == null || financials.isBlank() ? "(재무 데이터 미수집)" : financials);
        vars.put("시장뉴스", newsBlock.toString());
        vars.put("보유종목목록", heldBlock.toString());
        return aiPromptService.render(account.promptKey, vars)
                + "\n\n"
                + additionalAccountInstruction(account);
    }

    private String defaultAccountNote(String accountType) {
        return switch (accountType) {
            case ACCOUNT_ISA -> "ISA 계좌입니다. 절세 목적, 중장기 운용, 현금성 자산의 대기 비중을 함께 고려합니다.";
            case ACCOUNT_IRP -> "퇴직연금 IRP 계좌입니다. 은퇴자산 보존, 장기 복리, 변동성 관리, 현금성 자산 비중을 함께 고려합니다.";
            default -> "일반 주식 포트폴리오입니다. 성장성, 리스크, 분산, 비중 조정을 중심으로 분석합니다.";
        };
    }

    private String additionalAccountInstruction(AnalysisAccount account) {
        if (ACCOUNT_ISA.equals(account.type)) {
            return "추가 출력 지침: 이 보고서는 반드시 ISA 계좌 진단으로 작성하세요. "
                    + "일반 주식 포트폴리오라고 부르지 말고, 절세 계좌의 중장기 운용·현금성 자산 대기비중·과도한 매매 회전율을 함께 점검하세요. "
                    + "CASH 자산은 손익률이 아니라 리밸런싱 재원과 방어적 완충 역할로 해석하세요.";
        }
        if (ACCOUNT_IRP.equals(account.type)) {
            return "추가 출력 지침: 이 보고서는 반드시 퇴직연금 IRP 계좌 진단으로 작성하세요. "
                    + "일반 주식 포트폴리오라고 부르지 말고, 은퇴자산의 장기 안정성·분산·변동성 방어·현금성 자산 비중을 최우선으로 평가하세요. "
                    + "CASH 자산은 손익률이 아니라 안전자산/대기자금/리밸런싱 완충 역할로 해석하세요.";
        }
        return "추가 출력 지침: 코어/위성, core/satellite, 코어 종목, 위성 종목이라는 표현과 기준은 사용하지 마세요. "
                + "모든 판단은 현재 비중, 평가손익, 섹터/국가 집중도, 뉴스, 재무 데이터만 기준으로 설명하세요.";
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
                    .macroFit(text(root, "macroFit", null))
                    .grades(parseGrades(root.path("grades")))
                    .coreHolding(parseKeyHolding(root.path("coreHolding")))
                    .weakestLink(parseKeyHolding(root.path("weakestLink")))
                    .priorityActions(stringList(root.path("priorityActions")))
                    .bullScenario(parseScenario(root.path("bullScenario")))
                    .bearScenario(parseScenario(root.path("bearScenario")))
                    .selfRebuttal(text(root, "selfRebuttal", null))
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
                        .source(text(n, "source", "NEWS"))
                        .symbol(text(n, "symbol", ""))
                        .name(text(n, "name", ""))
                        .market(text(n, "market", ""))
                        .newsBasis(text(n, "newsBasis", ""))
                        .reason(text(n, "reason", ""))
                        .risks(text(n, "risks", ""))
                        .fitForPortfolio(text(n, "fitForPortfolio", ""))
                        .build());
                if (recs.size() >= 3) break;
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

    private Grades parseGrades(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        return Grades.builder()
                .diversification(parseGradeItem(node.path("diversification")))
                .risk(parseGradeItem(node.path("risk")))
                .growth(parseGradeItem(node.path("growth")))
                .build();
    }

    private GradeItem parseGradeItem(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        return GradeItem.builder()
                .grade(text(node, "grade", ""))
                .comment(text(node, "comment", ""))
                .build();
    }

    private KeyHolding parseKeyHolding(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        String sym = text(node, "symbol", "");
        String name = text(node, "name", "");
        String reason = text(node, "reason", "");
        if ((sym == null || sym.isBlank()) && (name == null || name.isBlank())
                && (reason == null || reason.isBlank())) {
            return null;
        }
        return KeyHolding.builder().symbol(sym).name(name).reason(reason).build();
    }

    private Scenario parseScenario(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        String trigger = text(node, "trigger", "");
        String outlook = text(node, "outlook", "");
        if ((trigger == null || trigger.isBlank()) && (outlook == null || outlook.isBlank())) {
            return null;
        }
        return Scenario.builder().trigger(trigger).outlook(outlook).build();
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
    private String fmtWeightPct(Double v) { return v == null ? "미입력" : String.format(Locale.US, "%.2f%%", v); }
    private String fmtMoney(Double v, String market) {
        if (v == null || v <= 0) return "미입력";
        return fmtNum(v, market) + ("KR".equalsIgnoreCase(market) ? " KRW" : " USD");
    }
    private String fmtKRWValue(Double v) {
        if (v == null || v <= 0) return "미입력";
        return String.format(Locale.KOREA, "%,d KRW", Math.round(v));
    }

    private boolean notBlank(String s) { return s != null && !s.isBlank(); }
    private String str(Object v) { return v == null ? null : String.valueOf(v); }
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

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // 보유 종목 컨텍스트 임시 객체
    private static class AnalysisAccount {
        final String type;
        final String label;
        final String promptKey;
        final String note;

        AnalysisAccount(String type, String label, String promptKey, String note) {
            this.type = type;
            this.label = label;
            this.promptKey = promptKey;
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
