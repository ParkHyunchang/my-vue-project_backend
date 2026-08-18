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
    private final Map<String, FundamentalSnapshot> cache = new ConcurrentHashMap<>();

    public KiwoomUsFundamentalService(YahooFinanceService yahoo) {
        this.yahoo = yahoo;
    }

    public Optional<FundamentalSnapshot> find(String symbol) {
        String key = normalize(symbol);
        if (key.isBlank()) return Optional.empty();
        LocalDateTime now = LocalDateTime.now();
        FundamentalSnapshot cached = cache.get(key);
        if (cached != null && cached.capturedAt().plus(FRESH_FOR).isAfter(now)) {
            return Optional.of(cached);
        }

        FundamentalSnapshot refreshed;
        try {
            refreshed = read(key, now);
        } catch (RuntimeException ignored) {
            refreshed = null;
        }
        if (refreshed != null) {
            cache.put(key, refreshed);
            return Optional.of(refreshed);
        }
        if (cached != null && cached.capturedAt().plus(STALE_FALLBACK_FOR).isAfter(now)) {
            return Optional.of(cached);
        }
        return Optional.empty();
    }

    private FundamentalSnapshot read(String symbol, LocalDateTime capturedAt) {
        JsonNode root = yahoo.fetchFundamentals(symbol);
        if (root == null) return null;
        JsonNode summary = root.path("summaryDetail");
        JsonNode stats = root.path("defaultKeyStatistics");
        JsonNode financial = root.path("financialData");
        double forwardPe = firstNumber(summary.path("forwardPE"), stats.path("forwardPE"));
        double trailingPe = number(summary.path("trailingPE"));
        double roe = number(financial.path("returnOnEquity"));
        if (!Double.isNaN(roe) && Math.abs(roe) <= 2) roe *= 100;
        double effectivePe = positive(forwardPe) ? forwardPe : trailingPe;
        if (!positive(effectivePe) || Double.isNaN(roe)) return null;
        return new FundamentalSnapshot(forwardPe, trailingPe, roe, capturedAt);
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
        return !Double.isNaN(value) && value > 0;
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
}
