package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * AI 분석 프롬프트에 주입할 '재무 데이터' 블록을 만든다.
 *  - US: Yahoo Finance quoteSummary (PER·PBR·ROE·마진·성장률·컨센서스)
 *  - KR: OpenDART (Stage 3에서 DartFinancialService 주입 예정)
 * 어떤 사유로든 실패하면 '데이터 없음' 안내 문자열을 돌려 분석이 멈추지 않게 한다.
 */
@Service
public class FinancialDataService {

    private static final Logger log = LoggerFactory.getLogger(FinancialDataService.class);

    private final YahooFinanceService yahoo;
    private final DartFinancialService dart;

    public FinancialDataService(YahooFinanceService yahoo, DartFinancialService dart) {
        this.yahoo = yahoo;
        this.dart = dart;
    }

    /** 단일 종목 재무 요약 (프롬프트 {{재무데이터}} 용). */
    public String stockSummary(String symbol, String market) {
        try {
            if ("US".equalsIgnoreCase(market)) {
                return usSummary(symbol);
            }
            // KR: OpenDART 재무제표
            String kr = dart.summary(symbol);
            if (kr != null) return kr;
            return dart.enabled()
                ? "(국내 종목 재무 데이터를 DART에서 찾지 못함 — 시세·뉴스 기반으로 분석하세요)"
                : "(국내 종목 재무 데이터 미연동 — 시세·뉴스 기반으로 분석하세요)";
        } catch (Exception e) {
            log.warn("[Financial] {} 재무 요약 실패: {}", symbol, e.getMessage());
            return "(재무 데이터 수집 실패)";
        }
    }

    /** 보유 종목들의 재무 한 줄 요약 묶음 (포트폴리오 {{재무데이터}} 용). */
    public String holdingsSummary(List<Holding> holdings) {
        if (holdings == null || holdings.isEmpty()) {
            return "(보유 종목 없음)";
        }
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (Holding h : holdings) {
            sb.append(i++).append(". ").append(h.name()).append(" (").append(h.symbol()).append("): ");
            try {
                sb.append("US".equalsIgnoreCase(h.market()) ? usOneLine(h.symbol()) : krOneLine(h.symbol()));
            } catch (Exception e) {
                sb.append("재무 데이터 없음");
            }
            sb.append("\n");
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
        addMoney(sb, "시가총액", num(pr.path("marketCap")));
        addRatio(sb, "PER(주가수익비율)", firstNum(sd.path("trailingPE"), sd.path("forwardPE")), "배");
        addRatio(sb, "PBR(주가순자산비율)", num(ks.path("priceToBook")), "배");
        addRatio(sb, "PEG", num(ks.path("pegRatio")), "");
        addPct(sb, "ROE(자기자본이익률)", num(fd.path("returnOnEquity")));
        addPct(sb, "영업이익률", num(fd.path("operatingMargins")));
        addPct(sb, "순이익률", num(fd.path("profitMargins")));
        addPct(sb, "매출성장률(YoY)", num(fd.path("revenueGrowth")));
        addRatio(sb, "부채비율(D/E)", num(fd.path("debtToEquity")), "");
        addPct(sb, "배당수익률", num(sd.path("dividendYield")));
        addMoney(sb, "매출(TTM)", num(fd.path("totalRevenue")));
        addMoney(sb, "잉여현금흐름", num(fd.path("freeCashflow")));

        String rec = fd.path("recommendationKey").asText("");
        double target = num(fd.path("targetMeanPrice"));
        if (!rec.isBlank() || !Double.isNaN(target)) {
            sb.append("애널리스트 컨센서스: ");
            if (!rec.isBlank()) sb.append(rec);
            if (!Double.isNaN(target)) sb.append(rec.isBlank() ? "" : ", ").append("목표주가 $").append(fmt2(target));
            sb.append("\n");
        }

        if (sb.length() == 0) return "(재무 데이터 미수집)";
        return "(출처: Yahoo Finance, 실시간)\n" + sb.toString().strip();
    }

    private String usOneLine(String symbol) {
        JsonNode root = yahoo.fetchFundamentals(symbol);
        if (root == null) return "재무 데이터 없음";
        JsonNode sd = root.path("summaryDetail");
        JsonNode ks = root.path("defaultKeyStatistics");
        JsonNode fd = root.path("financialData");
        StringBuilder sb = new StringBuilder();
        appendInline(sb, "PER", ratioStr(firstNum(sd.path("trailingPE"), sd.path("forwardPE")), "배"));
        appendInline(sb, "PBR", ratioStr(num(ks.path("priceToBook")), "배"));
        appendInline(sb, "ROE", pctStr(num(fd.path("returnOnEquity"))));
        appendInline(sb, "영업이익률", pctStr(num(fd.path("operatingMargins"))));
        appendInline(sb, "매출성장", pctStr(num(fd.path("revenueGrowth"))));
        return sb.length() == 0 ? "재무 데이터 없음" : sb.toString();
    }

    private String krOneLine(String symbol) {
        String block = dart.summary(symbol);
        if (block == null) return dart.enabled() ? "국내 재무 미수집" : "국내 재무 미연동";
        // 멀티라인 요약을 한 줄로 압축 (출처 줄 제외)
        String[] lines = block.split("\n");
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            String t = l.strip();
            if (t.isEmpty() || t.startsWith("(출처")) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(t);
        }
        return sb.length() == 0 ? "국내 재무 미수집" : sb.toString();
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
    private void addRatio(StringBuilder sb, String label, double v, String unit) {
        if (Double.isNaN(v)) return;
        sb.append(label).append(": ").append(fmt2(v)).append(unit).append("\n");
    }
    private void addPct(StringBuilder sb, String label, double v) {
        if (Double.isNaN(v)) return;
        sb.append(label).append(": ").append(String.format(Locale.US, "%.1f%%", v * 100)).append("\n");
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
    private String fmt2(double v) { return String.format(Locale.US, "%,.2f", v); }
    private String moneyUsd(double v) {
        if (v >= 1e12) return String.format(Locale.US, "$%.2fT", v / 1e12);
        if (v >= 1e9)  return String.format(Locale.US, "$%.2fB", v / 1e9);
        if (v >= 1e6)  return String.format(Locale.US, "$%.2fM", v / 1e6);
        return String.format(Locale.US, "$%,.0f", v);
    }
}
