package com.hyunchang.webapp.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Embeddable;

/** Immutable-at-entry parameters, shared by a buy proposal and its managed position. */
@Embeddable
public class KiwoomUsTrendExitPlan {
    private Double trendEntryAtr;
    private Double trendRiskPerShare;
    private Double trendTrailAtrMultiplier;
    private Double trendTrailActivationR;
    private Integer trendMaxHoldingTradingDays;

    protected KiwoomUsTrendExitPlan() {}

    public KiwoomUsTrendExitPlan(
            double atr, double risk, double trail, double activation, int days) {
        trendEntryAtr = atr;
        trendRiskPerShare = risk;
        trendTrailAtrMultiplier = trail;
        trendTrailActivationR = activation;
        trendMaxHoldingTradingDays = days;
        if (!isValid()) throw new IllegalArgumentException("유효하지 않은 추세 청산 계획");
    }

    @JsonIgnore
    public boolean isValid() {
        return positive(trendEntryAtr)
                && positive(trendRiskPerShare)
                && positive(trendTrailAtrMultiplier)
                && positive(trendTrailActivationR)
                && trendMaxHoldingTradingDays != null
                && trendMaxHoldingTradingDays > 0;
    }

    private boolean positive(Double value) {
        return value != null && Double.isFinite(value) && value > 0;
    }

    public Double getTrendEntryAtr() {
        return trendEntryAtr;
    }

    public Double getTrendRiskPerShare() {
        return trendRiskPerShare;
    }

    public Double getTrendTrailAtrMultiplier() {
        return trendTrailAtrMultiplier;
    }

    public Double getTrendTrailActivationR() {
        return trendTrailActivationR;
    }

    public Integer getTrendMaxHoldingTradingDays() {
        return trendMaxHoldingTradingDays;
    }

    public KiwoomUsTrendExitPlan copy() {
        return new KiwoomUsTrendExitPlan(
                trendEntryAtr,
                trendRiskPerShare,
                trendTrailAtrMultiplier,
                trendTrailActivationR,
                trendMaxHoldingTradingDays);
    }
}
