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
    private double swingStopLossPercent;
    private double swingTakeProfitPercent;
    private int swingMaxHoldingDays;
    private boolean riskLoopEnabled;
    private long dailyLossLimitAmount;
    private int dailyMaxProposals;
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

    public long getDailyLossLimitAmount() {
        return dailyLossLimitAmount;
    }

    public void setDailyLossLimitAmount(long v) {
        dailyLossLimitAmount = v;
    }

    public int getDailyMaxProposals() {
        return dailyMaxProposals;
    }

    public void setDailyMaxProposals(int v) {
        dailyMaxProposals = v;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
