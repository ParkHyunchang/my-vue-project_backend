package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * AI 분석 프롬프트에 주입할 '재무 데이터' 블록을 만든다.
 *  - US: Yahoo Finance quoteSummary (valuation·quality·profitability·positioning)
 *  - KR: OpenDART 재무제표 + 최근 공시 체크
 * 어떤 사유로든 실패하면 '데이터 없음' 안내 문자열을 돌려 분석이 멈추지 않게 한다.
 */
@Service
public class FinancialDataService {

    private static final Logger log = LoggerFactory.getLogger(FinancialDataService.class);

    // 보유 종목 재무 요약 병렬 수집 전용 풀 (외부 API 왕복이 병목이라 I/O 병렬화)
    private static final ExecutorService POOL = Executors.newFixedThreadPool(6);
    // 재무 요약 수집 전체 시간 예산 — 초과분은 "재무 데이터 없음"으로 분석 계속
    private static final long HOLDINGS_SUMMARY_BUDGET_MS = 20_000L;

    private final YahooFinanceService yahoo;
    private final DartFinancialService dart;
    private final NaverFinanceService naverFinanceService;
    private final KrxOpenApiService krxOpenApiService;

    public FinancialDataService(YahooFinanceService yahoo,
                                DartFinancialService dart,
                                NaverFinanceService naverFinanceService,
                                KrxOpenApiService krxOpenApiService) {
        this.yahoo = yahoo;
        this.dart = dart;
        this.naverFinanceService = naverFinanceService;
        this.krxOpenApiService = krxOpenApiService;
    }

    @PreDestroy
    void shutdownPool() {
        POOL.shutdown();
    }

    /** 단일 종목 재무 요약 (프롬프트 {{재무데이터}} 용). */
    public String stockSummary(String symbol, String market) {
        try {
            if ("US".equalsIgnoreCase(market)) {
                return usSummary(symbol);
            }
            // KR: OpenDART 재무제표
            String kr = dart.summary(symbol);
            if (kr != null) {
                String disclosures = dart.disclosureSummary(symbol);
                return kr + (disclosures == null || disclosures.isBlank() ? "" : "\n\n" + disclosures);
            }
            String fallback = krFallbackSummary(symbol);
            if (fallback != null) return fallback;
            return dart.enabled()
                ? "(국내 종목 재무 데이터를 DART에서 찾지 못함 — 시세·뉴스 기반으로 분석하세요)"
                : "(국내 종목 재무 데이터 미연동 — 시세·뉴스 기반으로 분석하세요)";
        } catch (Exception e) {
            log.warn("[Financial] {} 재무 요약 실패: {}", symbol, e.getMessage());
            return "(재무 데이터 수집 실패)";
        }
    }

    /**
     * 보유 종목들의 재무 한 줄 요약 묶음 (포트폴리오 {{재무데이터}} 용).
     * 종목당 외부 API 왕복이 있어 순차 조회 시 종합(4계좌) 분석이 프록시 타임아웃을 넘길 수 있으므로
     * 전용 풀에서 병렬 수집하고, 전체 시간 예산 초과분은 "재무 데이터 없음"으로 처리한다.
     */
    public String holdingsSummary(List<Holding> holdings) {
        if (holdings == null || holdings.isEmpty()) {
            return "(보유 종목 없음)";
        }
        List<CompletableFuture<String>> futures = holdings.stream()
                .map(h -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return "US".equalsIgnoreCase(h.market()) ? usOneLine(h.symbol()) : krOneLine(h.symbol());
                    } catch (Exception e) {
                        return "재무 데이터 없음";
                    }
                }, POOL))
                .toList();

        long deadline = System.currentTimeMillis() + HOLDINGS_SUMMARY_BUDGET_MS;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < holdings.size(); i++) {
            Holding h = holdings.get(i);
            String line;
            try {
                long remaining = Math.max(1, deadline - System.currentTimeMillis());
                line = futures.get(i).get(remaining, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                futures.get(i).cancel(true);
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                line = "재무 데이터 없음";
            }
            sb.append(i + 1).append(". ").append(h.name()).append(" (").append(h.symbol()).append("): ")
              .append(line).append("\n");
        }
        return sb.toString().strip();
    }

    /** 포트폴리오용 보유 종목 식별 정보. */
    public record Holding(String symbol, String name, String market) {}

    // ── US (Yahoo) ────────────────────────────────────────────────────────

    private String usSummary(String symbol) {
        JsonNode root = yahoo.fetchFundamentals(symbol);
        if (root == null) return "(재무 데이터 미수집 — Yahoo 조회 실패)";

        JsonNode sd = root.path("summaryDetail");
        JsonNode ks = root.path("defaultKeyStatistics");
        JsonNode fd = root.path("financialData");
        JsonNode pr = root.path("price");

        StringBuilder sb = new StringBuilder();
        sb.append("정량·밸류에이션 매트릭스\n");
        addMoney(sb, "- 시가총액", num(pr.path("marketCap")));
        addRatio(sb, "- Trailing PER", num(sd.path("trailingPE")), "배");
        addRatio(sb, "- 12M Forward PER", firstNum(sd.path("forwardPE"), ks.path("forwardPE")), "배");
        addRatio(sb, "- PBR", num(ks.path("priceToBook")), "배");
        addRatio(sb, "- PSR", firstNum(sd.path("priceToSalesTrailing12Months"), ks.path("priceToSalesTrailing12Months")), "배");
        addRatio(sb, "- EV/EBITDA", firstNum(ks.path("enterpriseToEbitda"), sd.path("enterpriseToEbitda")), "배");
        addRatio(sb, "- PEG", num(ks.path("pegRatio")), "");
        addPct(sb, "- 배당수익률", num(sd.path("dividendYield")));

        sb.append("수익성·자본 효율성\n");
        addPct(sb, "- ROE", num(fd.path("returnOnEquity")));
        addPct(sb, "- ROA", num(fd.path("returnOnAssets")));
        addPct(sb, "- 매출총이익률", num(fd.path("grossMargins")));
        addPct(sb, "- 영업이익률", num(fd.path("operatingMargins")));
        addPct(sb, "- 순이익률", num(fd.path("profitMargins")));
        addPct(sb, "- 매출성장률(YoY)", num(fd.path("revenueGrowth")));
        addPct(sb, "- 이익성장률(YoY)", num(fd.path("earningsGrowth")));

        sb.append("현금흐름·재무 안정성\n");
        addMoney(sb, "- 매출(TTM)", num(fd.path("totalRevenue")));
        addMoney(sb, "- 영업활동현금흐름(CFO)", num(fd.path("operatingCashflow")));
        addMoney(sb, "- 잉여현금흐름(FCF)", num(fd.path("freeCashflow")));
        addMoney(sb, "- 순이익(Net Income)", firstNum(ks.path("netIncomeToCommon"), fd.path("netIncomeToCommon")));
        addQualityRatio(sb, firstNum(fd.path("operatingCashflow")), firstNum(ks.path("netIncomeToCommon"), fd.path("netIncomeToCommon")));
        addRatio(sb, "- 부채비율(D/E)", num(fd.path("debtToEquity")), "");
        addRatio(sb, "- 유동비율", num(fd.path("currentRatio")), "");
        addRatio(sb, "- 당좌비율", num(fd.path("quickRatio")), "");

        sb.append("수급·포지셔닝 프록시\n");
        addRatio(sb, "- Short Ratio", num(ks.path("shortRatio")), "");
        addPct(sb, "- Short % of Float", num(ks.path("shortPercentOfFloat")));
        addCount(sb, "- 공매도 잔고 Shares Short", num(ks.path("sharesShort")));

        String rec = fd.path("recommendationKey").asText("");
        double target = num(fd.path("targetMeanPrice"));
        if (!rec.isBlank() || !Double.isNaN(target)) {
            sb.append("애널리스트 컨센서스: ");
            if (!rec.isBlank()) sb.append(rec);
            if (!Double.isNaN(target)) sb.append(rec.isBlank() ? "" : ", ").append("목표주가 $").append(fmt2(target));
            sb.append("\n");
        }

        sb.append("데이터 한계: 3~5개년 피어 시계열, 시장점유율/ASP 전가율, 컨퍼런스콜 원문은 현재 미수집입니다. 제공되지 않은 값은 추정하지 마세요.\n");

        if (sb.length() == 0) return "(재무 데이터 미수집)";
        return "(출처: Yahoo Finance, 실시간)\n" + sb.toString().strip();
    }

    private String usOneLine(String symbol) {
        String line = yahooOneLineOrNull(symbol);
        return line == null ? "재무 데이터 없음" : line;
    }

    private String yahooOneLineOrNull(String symbol) {
        JsonNode root = yahoo.fetchFundamentals(symbol);
        if (root == null) return null;
        JsonNode sd = root.path("summaryDetail");
        JsonNode ks = root.path("defaultKeyStatistics");
        JsonNode fd = root.path("financialData");
        StringBuilder sb = new StringBuilder();
        appendInline(sb, "Fwd PER", ratioStr(firstNum(sd.path("forwardPE"), ks.path("forwardPE")), "배"));
        appendInline(sb, "Trailing PER", ratioStr(num(sd.path("trailingPE")), "배"));
        appendInline(sb, "PBR", ratioStr(num(ks.path("priceToBook")), "배"));
        appendInline(sb, "PSR", ratioStr(firstNum(sd.path("priceToSalesTrailing12Months"), ks.path("priceToSalesTrailing12Months")), "배"));
        appendInline(sb, "EV/EBITDA", ratioStr(firstNum(ks.path("enterpriseToEbitda"), sd.path("enterpriseToEbitda")), "배"));
        appendInline(sb, "배당", pctStr(num(sd.path("dividendYield"))));
        appendInline(sb, "ROE", pctStr(num(fd.path("returnOnEquity"))));
        appendInline(sb, "영업이익률", pctStr(num(fd.path("operatingMargins"))));
        appendInline(sb, "매출성장", pctStr(num(fd.path("revenueGrowth"))));
        appendInline(sb, "CFO/NI", qualityRatioStr(firstNum(fd.path("operatingCashflow")), firstNum(ks.path("netIncomeToCommon"), fd.path("netIncomeToCommon"))));
        appendInline(sb, "ShortRatio", ratioStr(num(ks.path("shortRatio")), ""));
        return sb.length() == 0 ? null : sb.toString();
    }

    private String krOneLine(String symbol) {
        String block = dart.summary(symbol);
        if (block == null) {
            String fallback = krFallbackOneLine(symbol);
            if (fallback != null) return fallback;
            return dart.enabled() ? "국내 재무 미수집" : "국내 재무 미연동";
        }
        // 멀티라인 요약을 한 줄로 압축 (출처 줄 제외)
        String[] lines = block.split("\n");
        StringBuilder sb = new StringBuilder();
        int added = 0;
        for (String l : lines) {
            String t = l.strip();
            if (t.isEmpty() || t.startsWith("(출처")) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(t);
            if (++added >= 10) break;
        }
        String disclosures = dart.disclosureOneLine(symbol);
        if (disclosures != null && !disclosures.isBlank()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(disclosures);
        }
        return sb.length() == 0 ? "국내 재무 미수집" : sb.toString();
    }

    private String krFallbackSummary(String symbol) {
        String line = krFallbackOneLine(symbol);
        return line == null ? null : "(출처: KRX/Naver 보조 데이터)\n" + line;
    }

    private String krFallbackOneLine(String symbol) {
        NaverFinanceService.NaverEtfData etfData = safeFetchEtfData(symbol);
        boolean etp = etfData != null || isKrxEtp(symbol);

        StringBuilder sb = new StringBuilder();
        String etfLine = etfData == null ? null : etfDataLine(etfData);
        appendInline(sb, "ETF/ETN 상품지표", etfLine);
        appendInline(sb, "KRX 거래지표", krxTradeLine(symbol));
        if (etp || etfData != null) {
            appendInline(sb, "해석 기준",
                    "ETF/ETN은 DART 기업 재무제표 비대상. PER/PBR/ROE가 없으면 NAV·괴리율·분배금/배당·총보수·거래량·기초지수 중심으로 판단");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private boolean isKrxEtp(String symbol) {
        String normalized = normalizeSymbol(symbol);
        if (normalized == null) return false;
        String code = normalized.split("\\.")[0];
        if (code.chars().anyMatch(Character::isLetter)) return true;
        try {
            return krxOpenApiService.getAllEtp().stream()
                    .anyMatch(s -> normalized.equalsIgnoreCase(s.symbol()));
        } catch (Exception e) {
            log.debug("[Financial] KRX ETP 여부 조회 실패 [{}]: {}", symbol, e.getMessage());
            return false;
        }
    }

    private NaverFinanceService.NaverEtfData safeFetchEtfData(String symbol) {
        try {
            return naverFinanceService.fetchEtfData(symbol);
        } catch (Exception e) {
            log.debug("[Financial] Naver ETF 지표 조회 실패 [{}]: {}", symbol, e.getMessage());
            return null;
        }
    }

    private String etfDataLine(NaverFinanceService.NaverEtfData data) {
        List<String> parts = new ArrayList<>();
        addPart(parts, "NAV", data.nav());
        addPart(parts, "괴리율", data.divergenceRate());
        addPart(parts, "추적오차", data.trackingError());
        addPart(parts, "총보수", data.expenseRatio());
        addPart(parts, "분배금", data.distribution());
        addPart(parts, "배당/분배수익률", data.dividendYield());
        addPart(parts, "기초지수", data.underlyingIndex());
        addPart(parts, "순자산", data.netAssetValue());
        addPart(parts, "거래대금", data.tradeValue());
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }

    private String krxTradeLine(String symbol) {
        try {
            NaverFinanceService.NaverStockData data = krxOpenApiService.getKrPrice(symbol);
            if (data == null) return null;
            List<String> parts = new ArrayList<>();
            if (data.price() > 0) parts.add("현재가 " + priceKrw(data.price()));
            parts.add("일변동률 " + String.format(Locale.US, "%+.2f%%", data.changePercent()));
            if (data.marketCap() > 0) parts.add("시가총액 " + moneyKrw(data.marketCap()));
            if (data.volume() > 0) parts.add("거래량 " + String.format(Locale.KOREA, "%,d주", data.volume()));
            return parts.isEmpty() ? null : String.join(" · ", parts);
        } catch (Exception e) {
            log.debug("[Financial] KRX 거래지표 조회 실패 [{}]: {}", symbol, e.getMessage());
            return null;
        }
    }

    private void addPart(List<String> parts, String label, String value) {
        if (value != null && !value.isBlank()) parts.add(label + " " + value);
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) return null;
        return symbol.trim().toUpperCase(Locale.ROOT);
    }

    // ── 포맷 헬퍼 ──────────────────────────────────────────────────────────

    private double num(JsonNode node) { return yahoo.summaryNumber(node); }

    private double firstNum(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            double value = num(node);
            if (!Double.isNaN(value)) return value;
        }
        return Double.NaN;
    }

    private void addMoney(StringBuilder sb, String label, double v) {
        if (Double.isNaN(v) || v == 0) return;
        sb.append(label).append(": ").append(moneyUsd(v)).append("\n");
    }
    private void addCount(StringBuilder sb, String label, double v) {
        if (Double.isNaN(v) || v == 0) return;
        sb.append(label).append(": ").append(String.format(Locale.US, "%,.0f", v)).append("\n");
    }
    private void addRatio(StringBuilder sb, String label, double v, String unit) {
        if (Double.isNaN(v)) return;
        sb.append(label).append(": ").append(fmt2(v)).append(unit).append("\n");
    }
    private void addPct(StringBuilder sb, String label, double v) {
        if (Double.isNaN(v)) return;
        sb.append(label).append(": ").append(String.format(Locale.US, "%.1f%%", v * 100)).append("\n");
    }
    private void addQualityRatio(StringBuilder sb, double cfo, double netIncome) {
        String ratio = qualityRatioStr(cfo, netIncome);
        if (ratio == null) return;
        sb.append("- CFO/Net Income: ").append(ratio);
        if (netIncome > 0 && cfo / netIncome < 1.0) {
            sb.append(" (1.0 미만: 이익의 질 검증 필요)");
        }
        sb.append("\n");
    }
    private void appendInline(StringBuilder sb, String label, String value) {
        if (value == null) return;
        if (sb.length() > 0) sb.append(" · ");
        sb.append(label).append(' ').append(value);
    }
    private String ratioStr(double v, String unit) {
        return Double.isNaN(v) ? null : fmt2(v) + unit;
    }
    private String pctStr(double v) {
        return Double.isNaN(v) ? null : String.format(Locale.US, "%.1f%%", v * 100);
    }
    private String qualityRatioStr(double cfo, double netIncome) {
        if (Double.isNaN(cfo) || Double.isNaN(netIncome) || netIncome == 0) return null;
        return fmt2(cfo / netIncome) + "배";
    }
    private String fmt2(double v) { return String.format(Locale.US, "%,.2f", v); }
    private String priceKrw(double v) { return String.format(Locale.KOREA, "%,d원", Math.round(v)); }
    private String moneyKrw(long v) {
        if (v >= 1_0000_0000_0000L) {
            long jo = v / 1_0000_0000_0000L;
            long eok = (v % 1_0000_0000_0000L) / 1_0000_0000L;
            return eok > 0
                    ? String.format(Locale.KOREA, "%d조 %,d억", jo, eok)
                    : String.format(Locale.KOREA, "%d조", jo);
        }
        if (v >= 1_0000_0000L) {
            return String.format(Locale.KOREA, "%,d억", v / 1_0000_0000L);
        }
        return String.format(Locale.KOREA, "%,d원", v);
    }
    private String moneyUsd(double v) {
        if (v >= 1e12) return String.format(Locale.US, "$%.2fT", v / 1e12);
        if (v >= 1e9)  return String.format(Locale.US, "$%.2fB", v / 1e9);
        if (v >= 1e6)  return String.format(Locale.US, "$%.2fM", v / 1e6);
        return String.format(Locale.US, "$%,.0f", v);
    }
}
