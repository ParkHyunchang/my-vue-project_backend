package com.hyunchang.webapp.service;

import com.hyunchang.webapp.entity.KiwoomStrategyControlState;
import com.hyunchang.webapp.repository.KiwoomStrategyControlStateRepository;
import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/** Shared in-memory switch and single-flight guard for dry-run decisions. */
@Component
public class KiwoomAutoTradeState {
    private final KiwoomStrategyControlStateRepository controlStateRepository;
    private final AtomicBoolean autoTrading = new AtomicBoolean(false);
    private final AtomicBoolean deciding = new AtomicBoolean(false);
    private final AtomicBoolean emergencyStopped = new AtomicBoolean(false);
    private volatile LocalDateTime lastRunAt;
    public KiwoomAutoTradeState(KiwoomStrategyControlStateRepository controlStateRepository) { this.controlStateRepository = controlStateRepository; }
    @PostConstruct void restoreEmergencyStop() { emergencyStopped.set(controlStateRepository.findById(1L).map(KiwoomStrategyControlState::isEmergencyStopped).orElse(false)); }
    public boolean isAutoTrading() { return autoTrading.get(); }
    public void setAutoTrading(boolean enabled) { autoTrading.set(enabled); }
    public boolean tryStartDecision() { return deciding.compareAndSet(false, true); }
    public void finishDecision() { deciding.set(false); }
    public boolean isDeciding() { return deciding.get(); }
    public boolean isEmergencyStopped() { return emergencyStopped.get(); }
    public void emergencyStop() { autoTrading.set(false); emergencyStopped.set(true); persistEmergencyStop(true); }
    public void clearEmergencyStop() { emergencyStopped.set(false); persistEmergencyStop(false); }
    private void persistEmergencyStop(boolean stopped) { KiwoomStrategyControlState entity = controlStateRepository.findById(1L).orElseGet(KiwoomStrategyControlState::new); entity.setEmergencyStopped(stopped); controlStateRepository.save(entity); }
    public LocalDateTime getLastRunAt() { return lastRunAt; }
    public void markRun() { lastRunAt = LocalDateTime.now(); }
}
