package com.hyunchang.webapp.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "kiwoom_strategy_settings")
public class KiwoomStrategySettings {
    @Id private Long id = 1L;
    private boolean autoExecute;
    private int autoExecuteMinConfidence;
    private double maxBuyDepositPercent;
    private int candidateReevaluationMinutes = 60;
    private double swingMinChangePercent = 2.0;
    private double swingMaxChangePercent = 8.0;
    private double swingMinVolumeRatio = 2.0;
    private double swingMaxVolumeRatio = 5.0;
    private long minMarketCapWon = 300_000_000_000L;
    private long minTradingValueWon = 10_000_000_000L;
    private double maxSpreadPercent = 0.3;
    private double maxPriceAboveMa20Percent = 10.0;
    private double maxAtrPercent = 4.0;
    private double swingStopLossPercent;
    private double swingTakeProfitPercent;
    private double swingTakeProfitPercent2;
    private double swingTakeProfitSplitPercent = 50.0;
    private int swingMaxHoldingDays;
    private boolean riskLoopEnabled;
    private double dailyLossLimitPercent;
    private int dailyMaxProposals;
    private int maxPositions = 3;
    private int maxPositionsPerSector = 1;
    private int stopLossCooldownTradingDays = 5;
    private int dailyStopLossLimit = 2;

    /** null은 기능 추가 전 기존 행이다. 서비스 초기화에서 안전 기본값 true로 백필한다. */
    private Boolean requireCatalystForAutoBuy = true;

    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public boolean isAutoExecute() {
        return autoExecute;
    }

    public void setAutoExecute(boolean v) {
        autoExecute = v;
    }

    public int getAutoExecuteMinConfidence() {
        return autoExecuteMinConfidence;
    }

    public void setAutoExecuteMinConfidence(int v) {
        autoExecuteMinConfidence = v;
    }

    public double getMaxBuyDepositPercent() {
        return maxBuyDepositPercent;
    }

    public void setMaxBuyDepositPercent(double v) {
        maxBuyDepositPercent = v;
    }

    public int getCandidateReevaluationMinutes() {
        return candidateReevaluationMinutes;
    }

    public void setCandidateReevaluationMinutes(int v) {
        candidateReevaluationMinutes = v;
    }

    public double getSwingMinChangePercent() {
        return swingMinChangePercent;
    }

    public void setSwingMinChangePercent(double v) {
        swingMinChangePercent = v;
    }

    public double getSwingMaxChangePercent() {
        return swingMaxChangePercent;
    }

    public void setSwingMaxChangePercent(double v) {
        swingMaxChangePercent = v;
    }

    public double getSwingMinVolumeRatio() {
        return swingMinVolumeRatio;
    }

    public void setSwingMinVolumeRatio(double v) {
        swingMinVolumeRatio = v;
    }

    public double getSwingMaxVolumeRatio() {
        return swingMaxVolumeRatio;
    }

    public void setSwingMaxVolumeRatio(double v) {
        swingMaxVolumeRatio = v;
    }

    public long getMinMarketCapWon() {
        return minMarketCapWon;
    }

    public void setMinMarketCapWon(long v) {
        minMarketCapWon = v;
    }

    public long getMinTradingValueWon() {
        return minTradingValueWon;
    }

    public void setMinTradingValueWon(long v) {
        minTradingValueWon = v;
    }

    public double getMaxSpreadPercent() {
        return maxSpreadPercent;
    }

    public void setMaxSpreadPercent(double v) {
        maxSpreadPercent = v;
    }

    public double getMaxPriceAboveMa20Percent() {
        return maxPriceAboveMa20Percent;
    }

    public void setMaxPriceAboveMa20Percent(double v) {
        maxPriceAboveMa20Percent = v;
    }

    public double getMaxAtrPercent() {
        return maxAtrPercent;
    }

    public void setMaxAtrPercent(double v) {
        maxAtrPercent = v;
    }

    public double getSwingStopLossPercent() {
        return swingStopLossPercent;
    }

    public void setSwingStopLossPercent(double v) {
        swingStopLossPercent = v;
    }

    public double getSwingTakeProfitPercent() {
        return swingTakeProfitPercent;
    }

    public void setSwingTakeProfitPercent(double v) {
        swingTakeProfitPercent = v;
    }

    public double getSwingTakeProfitPercent2() {
        return swingTakeProfitPercent2;
    }

    public void setSwingTakeProfitPercent2(double v) {
        swingTakeProfitPercent2 = v;
    }

    public double getSwingTakeProfitSplitPercent() {
        return swingTakeProfitSplitPercent;
    }

    public void setSwingTakeProfitSplitPercent(double v) {
        swingTakeProfitSplitPercent = v;
    }

    public int getSwingMaxHoldingDays() {
        return swingMaxHoldingDays;
    }

    public void setSwingMaxHoldingDays(int v) {
        swingMaxHoldingDays = v;
    }

    public boolean isRiskLoopEnabled() {
        return riskLoopEnabled;
    }

    public void setRiskLoopEnabled(boolean v) {
        riskLoopEnabled = v;
    }

    public double getDailyLossLimitPercent() {
        return dailyLossLimitPercent;
    }

    public void setDailyLossLimitPercent(double v) {
        dailyLossLimitPercent = v;
    }

    public int getDailyMaxProposals() {
        return dailyMaxProposals;
    }

    public void setDailyMaxProposals(int v) {
        dailyMaxProposals = v;
    }

    public int getMaxPositions() {
        return maxPositions;
    }

    public void setMaxPositions(int v) {
        maxPositions = v;
    }

    public int getMaxPositionsPerSector() {
        return maxPositionsPerSector;
    }

    public void setMaxPositionsPerSector(int v) {
        maxPositionsPerSector = v;
    }

    public int getStopLossCooldownTradingDays() {
        return stopLossCooldownTradingDays;
    }

    public void setStopLossCooldownTradingDays(int v) {
        stopLossCooldownTradingDays = v;
    }

    public int getDailyStopLossLimit() {
        return dailyStopLossLimit;
    }

    public void setDailyStopLossLimit(int v) {
        dailyStopLossLimit = v;
    }

    public Boolean getRequireCatalystForAutoBuy() {
        return requireCatalystForAutoBuy;
    }

    public boolean isRequireCatalystForAutoBuy() {
        return requireCatalystForAutoBuy == null || requireCatalystForAutoBuy;
    }

    public void setRequireCatalystForAutoBuy(boolean v) {
        requireCatalystForAutoBuy = v;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
