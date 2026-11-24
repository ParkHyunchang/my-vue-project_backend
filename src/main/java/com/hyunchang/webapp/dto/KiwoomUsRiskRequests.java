package com.hyunchang.webapp.dto;

import java.math.BigDecimal;

public final class KiwoomUsRiskRequests {
    private KiwoomUsRiskRequests() {}

    public record Activate(boolean confirmAllHoldings, boolean confirmCapitalFlows) {}

    public record Review(boolean required) {}

    public record CapitalFlow(String reference, BigDecimal amountUsd, boolean confirm) {}
}
