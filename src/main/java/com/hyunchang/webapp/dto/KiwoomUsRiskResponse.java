package com.hyunchang.webapp.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record KiwoomUsRiskResponse(
        boolean enabled,
        BigDecimal equityUsd,
        BigDecimal peakEquityUsd,
        BigDecimal drawdownPercent,
        int limitPercent,
        boolean triggered,
        boolean liquidationPending,
        boolean capitalFlowConfirmed,
        OffsetDateTime observedAt,
        String message) {}
