package com.hyunchang.webapp.dto.paper;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class UsPaperDtos {
    private UsPaperDtos() {}

    public record CreateRequest(
            @NotNull @DecimalMin("100") @DecimalMax("100000000") BigDecimal initialCashUsd) {}

    public record Fill(
            Long id,
            Instant at,
            String side,
            String symbol,
            long quantity,
            BigDecimal priceUsd,
            BigDecimal feeUsd,
            String reason) {}

    public record Equity(
            Instant at,
            BigDecimal equityUsd,
            BigDecimal strategyReturnPercent,
            BigDecimal benchmarkReturnPercent) {}

    public record Event(Instant at, String type, String message) {}

    public record Status(
            Long id,
            String state,
            boolean paperOnly,
            String strategy,
            BigDecimal initialCashUsd,
            BigDecimal cashUsd,
            long quantity,
            String symbol,
            BigDecimal lastPriceUsd,
            BigDecimal equityUsd,
            BigDecimal peakEquityUsd,
            BigDecimal drawdownPercent,
            BigDecimal maxDrawdownPercent,
            boolean hardLatched,
            boolean liquidationPending,
            int feeBps,
            int slippageBps,
            Instant createdAt,
            Instant startedAt,
            Instant observedAt,
            Instant quotedAt,
            boolean quoteFresh,
            boolean marketOpen,
            String reason,
            String message,
            String source,
            String benchmarkLabel,
            BigDecimal strategyReturnPercent,
            BigDecimal benchmarkReturnPercent,
            BigDecimal excessReturnPoints,
            boolean schedulerEnabled,
            List<Fill> fills,
            List<Equity> history,
            List<Event> events) {}
}
