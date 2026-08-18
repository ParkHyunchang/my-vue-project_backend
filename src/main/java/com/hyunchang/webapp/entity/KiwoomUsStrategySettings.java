package com.hyunchang.webapp.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "kiwoom_us_strategy_settings")
public class KiwoomUsStrategySettings {
    @Id private Long id = 1L;
    @JsonIgnore private boolean autoExecute = true;
    private double maxOrderUsd = 200;
    private double maxAllocatedUsd = 400;
    private double maxOrderPercent = 10;
    private double maxAllocationPercent = 20;
    private int maxPositions = 3;
    private int dailyMaxBuys = 2;
    private double minChangePercent = 2;
    private double maxChangePercent = 8;
    private double minVolumeRatio = 1.2;
    private boolean fundamentalFilterEnabled = true;
    private double maxForwardPe = 50;
    private double minRoePercent = 10;
    private double maxSpreadPercent = 0.15;
    private double stopLossPercent = 3;
    private double takeProfitPercent = 5;
    private double takeProfitPercent2 = 8;
    private int maxHoldingDays = 5;
    private int symbolCooldownDays = 5;
    private double dailyLossLimitPercent = 3;
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

    @JsonIgnore
    public double getMaxOrderUsd() {
        return maxOrderUsd;
    }

    public void setMaxOrderUsd(double v) {
        maxOrderUsd = v;
    }

    @JsonIgnore
    public double getMaxAllocatedUsd() {
        return maxAllocatedUsd;
    }

    public void setMaxAllocatedUsd(double v) {
        maxAllocatedUsd = v;
    }

    public double getMaxOrderPercent() {
        return maxOrderPercent;
    }

    public void setMaxOrderPercent(double v) {
        maxOrderPercent = v;
    }

    /** 기존 DB 열 호환용 값이며 신규 전략에서는 사용하지 않습니다. */
    @JsonIgnore
    public double getMaxAllocationPercent() {
        return maxAllocationPercent;
    }

    public void setMaxAllocationPercent(double v) {
        maxAllocationPercent = v;
    }

    public int getMaxPositions() {
        return maxPositions;
    }

    public void setMaxPositions(int v) {
        maxPositions = v;
    }

    public int getDailyMaxBuys() {
        return dailyMaxBuys;
    }

    public void setDailyMaxBuys(int v) {
        dailyMaxBuys = v;
    }

    public double getMinChangePercent() {
        return minChangePercent;
    }

    public void setMinChangePercent(double v) {
        minChangePercent = v;
    }

    public double getMaxChangePercent() {
        return maxChangePercent;
    }

    public void setMaxChangePercent(double v) {
        maxChangePercent = v;
    }

    public double getMinVolumeRatio() {
        return minVolumeRatio;
    }

    public void setMinVolumeRatio(double v) {
        minVolumeRatio = v;
    }

    public boolean isFundamentalFilterEnabled() {
        return fundamentalFilterEnabled;
    }

    public void setFundamentalFilterEnabled(boolean v) {
        fundamentalFilterEnabled = v;
    }

    public double getMaxForwardPe() {
        return maxForwardPe;
    }

    public void setMaxForwardPe(double v) {
        maxForwardPe = v;
    }

    public double getMinRoePercent() {
        return minRoePercent;
    }

    public void setMinRoePercent(double v) {
        minRoePercent = v;
    }

    public double getMaxSpreadPercent() {
        return maxSpreadPercent;
    }

    public void setMaxSpreadPercent(double v) {
        maxSpreadPercent = v;
    }

    public double getStopLossPercent() {
        return stopLossPercent;
    }

    public void setStopLossPercent(double v) {
        stopLossPercent = v;
    }

    public double getTakeProfitPercent() {
        return takeProfitPercent;
    }

    public void setTakeProfitPercent(double v) {
        takeProfitPercent = v;
    }

    public double getTakeProfitPercent2() {
        return takeProfitPercent2;
    }

    public void setTakeProfitPercent2(double v) {
        takeProfitPercent2 = v;
    }

    public int getMaxHoldingDays() {
        return maxHoldingDays;
    }

    public void setMaxHoldingDays(int v) {
        maxHoldingDays = v;
    }

    public int getSymbolCooldownDays() {
        return symbolCooldownDays;
    }

    public void setSymbolCooldownDays(int v) {
        symbolCooldownDays = v;
    }

    public double getDailyLossLimitPercent() {
        return dailyLossLimitPercent;
    }

    public void setDailyLossLimitPercent(double v) {
        dailyLossLimitPercent = v;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
