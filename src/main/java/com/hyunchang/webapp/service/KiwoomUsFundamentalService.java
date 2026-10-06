package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/** 미국 자동매매 후보의 PER·ROE를 마지막 정상값과 함께 캐시한다. */
@Service
public class KiwoomUsFundamentalService {
    private static final Duration FRESH_FOR = Duration.ofDays(1);
    private static final Duration STALE_FALLBACK_FOR = Duration.ofDays(7);

    private final YahooFinanceService yahoo;
    private final KiwoomUsReferenceStore store;
    private final Map<String, FundamentalSnapshot> cache = new ConcurrentHashMap<>();

    public KiwoomUsFundamentalService(YahooFinanceService yahoo, KiwoomUsReferenceStore store) {
        this.yahoo = yahoo;
        this.store = store;
    }

    public Optional<FundamentalSnapshot> find(String symbol) {
        return Optional.ofNullable(lookup(symbol).snapshot());
    }

    public LookupResult lookup(String symbol) {
        String key = normalize(symbol);
        if (key.isBlank())
            return new LookupResult(null, LookupStatus.DATA_UNAVAILABLE, "INPUT", "종목코드가 비어 있습니다.");
        LocalDateTime now = LocalDateTime.now();
        FundamentalSnapshot cached = cache.get(key);
        String cacheSource = "MEMORY";
        if (cached == null) {
            cached =
                    store.read("fundamental-" + key, FundamentalSnapshot.class)
                            .filter(
                                    value ->
                                            value.capturedAt() != null
                                                    && positive(value.effectivePe())
                                                    && Double.isFinite(value.roePercent()))
                            .orElse(null);
            if (cached != null) {
                cache.put(key, cached);
                cacheSource = "DISK";
            }
        }
        if (cached != null && cached.capturedAt().plus(FRESH_FOR).isAfter(now)) {
            return new LookupResult(
                    cached,
                    LookupStatus.CACHE_FRESH,
                    cacheSource,
                    "정상 캐시 사용(나이 " + cacheAge(cached, now) + ")");
        }

        JsonNode root;
        String failureDetail = "";
        try {
            root = yahoo.fetchFundamentals(key);
        } catch (RuntimeException error) {
            root = null;
            failureDetail = "Yahoo 호출 예외=" + error.getClass().getSimpleName();
        }
        YahooFinanceService.FundamentalsDiagnostic diagnostic =
                yahoo.getFundamentalsDiagnostic(key);
        FundamentalSnapshot refreshed = parse(root, now);
        if (refreshed != null) {
            cache.put(key, refreshed);
            store.write("fundamental-" + key, refreshed);
            String source = diagnostic == null ? "YAHOO" : diagnostic.source();
            String detail = diagnostic == null ? "Yahoo 재무정보 조회 성공" : diagnostic.detail();
            LookupStatus status =
                    diagnostic != null && "CACHE_FRESH".equals(diagnostic.status())
                            ? LookupStatus.CACHE_FRESH
                            : LookupStatus.YAHOO_REFRESHED;
            return new LookupResult(refreshed, status, source, detail);
        }
        if (failureDetail.isBlank()) failureDetail = failureDetail(root, diagnostic);
        if (cached != null && cached.capturedAt().plus(STALE_FALLBACK_FOR).isAfter(now)) {
            return new LookupResult(
                    cached,
                    LookupStatus.CACHE_STALE_FALLBACK,
                    cacheSource,
                    "새 조회 사용 불가("
                            + failureDetail
                            + "), 이전 정상 캐시 사용(나이 "
                            + cacheAge(cached, now)
                            + ")");
        }
        if (cached != null)
            return new LookupResult(
                    null,
                    LookupStatus.CACHE_EXPIRED,
                    cacheSource,
                    "정상 캐시 만료(나이 " + cacheAge(cached, now) + "), 새 조회 사용 불가=" + failureDetail);
        return new LookupResult(
                null,
                root == null ? LookupStatus.REQUEST_FAILED : LookupStatus.FIELDS_MISSING,
                diagnostic == null ? "YAHOO" : diagnostic.source(),
                failureDetail);
    }

    private FundamentalSnapshot parse(JsonNode root, LocalDateTime capturedAt) {
        if (root == null) return null;
        JsonNode summary = root.path("summaryDetail");
        JsonNode stats = root.path("defaultKeyStatistics");
        JsonNode financial = root.path("financialData");
        double forwardPe = firstNumber(summary.path("forwardPE"), stats.path("forwardPE"));
        double trailingPe = firstNumber(summary.path("trailingPE"), stats.path("trailingPE"));
        JsonNode roeNode = financial.path("returnOnEquity");
        double roe = number(roeNode);
        // Yahoo raw/numeric returnOnEquity is a ratio, including ratios above 2.
        if (!roeNode.isTextual() || !roeNode.asText().contains("%")) roe *= 100;
        double effectivePe = positive(forwardPe) ? forwardPe : trailingPe;
        if (!positive(effectivePe) || !Double.isFinite(roe)) return null;
        return new FundamentalSnapshot(forwardPe, trailingPe, roe, capturedAt);
    }

    private String failureDetail(
            JsonNode root, YahooFinanceService.FundamentalsDiagnostic diagnostic) {
        if (root == null)
            return diagnostic == null
                    ? "Yahoo 응답 없음"
                    : diagnostic.status() + "/" + diagnostic.source() + ": " + diagnostic.detail();
        JsonNode summary = root.path("summaryDetail");
        JsonNode stats = root.path("defaultKeyStatistics");
        boolean peMissing =
                !positive(
                        firstNumber(
                                summary.path("forwardPE"),
                                stats.path("forwardPE"),
                                summary.path("trailingPE"),
                                stats.path("trailingPE")));
        boolean roeMissing =
                Double.isNaN(number(root.path("financialData").path("returnOnEquity")));
        String missing =
                peMissing && roeMissing
                        ? "PER·ROE"
                        : peMissing ? "PER" : roeMissing ? "ROE" : "유효값";
        String source = diagnostic == null ? "Yahoo" : diagnostic.source();
        return source + " 응답 성공, " + missing + " 필드 누락";
    }

    private String cacheAge(FundamentalSnapshot snapshot, LocalDateTime now) {
        long hours = Math.max(0, Duration.between(snapshot.capturedAt(), now).toHours());
        return hours < 24 ? hours + "시간" : (hours / 24) + "일";
    }

    private double firstNumber(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            double value = number(node);
            if (!Double.isNaN(value)) return value;
        }
        return Double.NaN;
    }

    private double number(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return Double.NaN;
        JsonNode raw = node.path("raw");
        if (raw.isNumber()) return raw.asDouble();
        if (node.isNumber()) return node.asDouble();
        try {
            return Double.parseDouble(node.asText().replace(",", "").replace("%", "").trim());
        } catch (RuntimeException ignored) {
            return Double.NaN;
        }
    }

    private boolean positive(double value) {
        return Double.isFinite(value) && value > 0;
    }

    private String normalize(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }

    public record FundamentalSnapshot(
            double forwardPe, double trailingPe, double roePercent, LocalDateTime capturedAt) {
        public double effectivePe() {
            return !Double.isNaN(forwardPe) && forwardPe > 0 ? forwardPe : trailingPe;
        }
    }

    public enum LookupStatus {
        CACHE_FRESH,
        YAHOO_REFRESHED,
        CACHE_STALE_FALLBACK,
        FIELDS_MISSING,
        REQUEST_FAILED,
        CACHE_EXPIRED,
        DATA_UNAVAILABLE
    }

    public record LookupResult(
            FundamentalSnapshot snapshot, LookupStatus status, String source, String detail) {
        public String auditMessage() {
            return "상태=" + status + ", 출처=" + source + ", " + detail;
        }
    }
}
