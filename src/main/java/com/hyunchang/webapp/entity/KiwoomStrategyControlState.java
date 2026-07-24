package com.hyunchang.webapp.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "kiwoom_strategy_control_state")
public class KiwoomStrategyControlState {
    @Id private Long id = 1L;
    private boolean autoTradingEnabled;
    private boolean emergencyStopped;
    private int consecutiveApiFailures;
    private LocalDateTime lastApiFailureAt;
    private String lastApiFailureMessage;

    // 일일 손실 한도 — 거래일 최초 체크 시 총자산(예수금+평가금액)을 스냅샷으로 잡고, 이후 하락폭을 비교한다.
    private LocalDate dailyLossSnapshotDate;
    private long dailyLossBaseAsset;
    private long dailyLossLastAsset;
    private LocalDateTime dailyLossLastCheckedAt;
    private boolean dailyLossTriggered;

    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }

    public boolean isEmergencyStopped() {
        return emergencyStopped;
    }

    public boolean isAutoTradingEnabled() {
        return autoTradingEnabled;
    }

    public void setAutoTradingEnabled(boolean value) {
        autoTradingEnabled = value;
    }

    public void setEmergencyStopped(boolean value) {
        emergencyStopped = value;
    }

    public int getConsecutiveApiFailures() {
        return consecutiveApiFailures;
    }

    public void setConsecutiveApiFailures(int value) {
        consecutiveApiFailures = value;
    }

    public LocalDateTime getLastApiFailureAt() {
        return lastApiFailureAt;
    }

    public void setLastApiFailureAt(LocalDateTime value) {
        lastApiFailureAt = value;
    }

    public String getLastApiFailureMessage() {
        return lastApiFailureMessage;
    }

    public void setLastApiFailureMessage(String value) {
        lastApiFailureMessage = value;
    }

    public LocalDate getDailyLossSnapshotDate() {
        return dailyLossSnapshotDate;
    }

    public void setDailyLossSnapshotDate(LocalDate v) {
        dailyLossSnapshotDate = v;
    }

    public long getDailyLossBaseAsset() {
        return dailyLossBaseAsset;
    }

    public void setDailyLossBaseAsset(long v) {
        dailyLossBaseAsset = v;
    }

    public long getDailyLossLastAsset() {
        return dailyLossLastAsset;
    }

    public void setDailyLossLastAsset(long v) {
        dailyLossLastAsset = v;
    }

    public LocalDateTime getDailyLossLastCheckedAt() {
        return dailyLossLastCheckedAt;
    }

    public void setDailyLossLastCheckedAt(LocalDateTime v) {
        dailyLossLastCheckedAt = v;
    }

    public boolean isDailyLossTriggered() {
        return dailyLossTriggered;
    }

    public void setDailyLossTriggered(boolean v) {
        dailyLossTriggered = v;
    }
}
