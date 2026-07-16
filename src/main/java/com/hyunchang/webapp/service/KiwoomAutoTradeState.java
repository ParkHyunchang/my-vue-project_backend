package com.hyunchang.webapp.service;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/** Shared in-memory switch and single-flight guard for dry-run decisions. */
@Component
public class KiwoomAutoTradeState {
    private final AtomicBoolean autoTrading = new AtomicBoolean(false);
    private final AtomicBoolean deciding = new AtomicBoolean(false);
    private final AtomicBoolean emergencyStopped = new AtomicBoolean(false);
    private volatile LocalDateTime lastRunAt;
    public boolean isAutoTrading() { return autoTrading.get(); }
    public void setAutoTrading(boolean enabled) { autoTrading.set(enabled); }
    public boolean tryStartDecision() { return deciding.compareAndSet(false, true); }
    public void finishDecision() { deciding.set(false); }
    public boolean isDeciding() { return deciding.get(); }
    public boolean isEmergencyStopped() { return emergencyStopped.get(); }
    public void emergencyStop() { autoTrading.set(false); emergencyStopped.set(true); }
    public void clearEmergencyStop() { emergencyStopped.set(false); }
    public LocalDateTime getLastRunAt() { return lastRunAt; }
    public void markRun() { lastRunAt = LocalDateTime.now(); }
}
