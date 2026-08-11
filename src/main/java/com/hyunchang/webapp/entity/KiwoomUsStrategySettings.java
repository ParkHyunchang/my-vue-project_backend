package com.hyunchang.webapp.entity;

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
    private boolean autoExecute = true;
    private double maxOrderUsd = 200;
    private double maxAllocatedUsd = 400;
    private int maxPositions = 2;
    private int dailyMaxBuys = 1;
    private double minChangePercent = 1;
    private double maxChangePercent = 4;
    private double minVolumeRatio = 1.5;
    private double maxSpreadPercent = 0.15;
    private double stopLossPercent = 2.5;
    private double takeProfitPercent = 4;
    private double takeProfitPercent2 = 7;
    private int maxHoldingDays = 3;
    private int symbolCooldownDays = 5;
    private double dailyLossLimitPercent = 2;
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

    public double getMaxOrderUsd() {
        return maxOrderUsd;
    }

    public void setMaxOrderUsd(double v) {
        maxOrderUsd = v;
    }

    public double getMaxAllocatedUsd() {
        return maxAllocatedUsd;
    }

    public void setMaxAllocatedUsd(double v) {
        maxAllocatedUsd = v;
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
