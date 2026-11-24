package com.hyunchang.webapp.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Read-only broker data; missing financial values remain null. */
public record KiwoomAccountOverviewResponse(
        String broker, Instant queriedAt, List<Market> markets) {
    public record Market(
            String market,
            String status,
            String currency,
            String source,
            Instant queriedAt,
            Instant dataAsOf,
            List<Holding> holdings,
            BigDecimal cash,
            String notice) {}

    public record Holding(
            String symbol,
            String name,
            BigDecimal quantity,
            BigDecimal averagePrice,
            BigDecimal currentPrice,
            BigDecimal profitLoss,
            BigDecimal profitLossPercent,
            String currency) {}
}
