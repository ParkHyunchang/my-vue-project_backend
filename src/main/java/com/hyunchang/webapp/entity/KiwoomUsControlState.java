package com.hyunchang.webapp.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "kiwoom_us_strategy_control_state")
public class KiwoomUsControlState {
    @Id private Long id = 1L;
    private boolean autoTradingEnabled;
    private boolean emergencyStopped;
    private int consecutiveApiFailures;
    private LocalDateTime lastApiFailureAt;
    private String lastApiFailureMessage;
    private LocalDate dailyLossDate;
    private double dailyLossBaseAssetUsd;
    private boolean dailyLossTriggered;

    public Long getId() {
        return id;
    }

    public boolean isAutoTradingEnabled() {
        return autoTradingEnabled;
    }

    public void setAutoTradingEnabled(boolean v) {
        autoTradingEnabled = v;
    }

    public boolean isEmergencyStopped() {
        return emergencyStopped;
    }

    public void setEmergencyStopped(boolean v) {
        emergencyStopped = v;
    }

    public int getConsecutiveApiFailures() {
        return consecutiveApiFailures;
    }

    public void setConsecutiveApiFailures(int v) {
        consecutiveApiFailures = v;
    }

    public LocalDateTime getLastApiFailureAt() {
        return lastApiFailureAt;
    }

    public void setLastApiFailureAt(LocalDateTime v) {
        lastApiFailureAt = v;
    }

    public String getLastApiFailureMessage() {
        return lastApiFailureMessage;
    }

    public void setLastApiFailureMessage(String v) {
        lastApiFailureMessage = v;
    }

    public LocalDate getDailyLossDate() {
        return dailyLossDate;
    }

    public void setDailyLossDate(LocalDate v) {
        dailyLossDate = v;
    }

    public double getDailyLossBaseAssetUsd() {
        return dailyLossBaseAssetUsd;
    }

    public void setDailyLossBaseAssetUsd(double v) {
        dailyLossBaseAssetUsd = v;
    }

    public boolean isDailyLossTriggered() {
        return dailyLossTriggered;
    }

    public void setDailyLossTriggered(boolean v) {
        dailyLossTriggered = v;
    }
}
