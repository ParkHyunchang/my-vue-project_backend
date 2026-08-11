package com.hyunchang.webapp.service.kiwoom;

import com.hyunchang.webapp.entity.KiwoomUsControlState;
import com.hyunchang.webapp.repository.KiwoomUsControlStateRepository;
import com.hyunchang.webapp.util.KiwoomUsMarketHours;
import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

@Component
public class KiwoomUsAutoTradeState {
    private final KiwoomUsControlStateRepository repository;
    private final AtomicBoolean autoTrading = new AtomicBoolean();
    private final AtomicBoolean deciding = new AtomicBoolean();
    private final AtomicBoolean emergencyStopped = new AtomicBoolean();
    private final Map<String, Integer> apiFailureCounts = new HashMap<>();
    private volatile int consecutiveApiFailures;
    private volatile LocalDateTime lastApiFailureAt;
    private volatile String lastApiFailureMessage;

    public KiwoomUsAutoTradeState(KiwoomUsControlStateRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    void restore() {
        var s = repository.findById(1L).orElse(null);
        if (s != null) {
            autoTrading.set(s.isAutoTradingEnabled());
            emergencyStopped.set(s.isEmergencyStopped());
            consecutiveApiFailures = s.getConsecutiveApiFailures();
            lastApiFailureAt = s.getLastApiFailureAt();
            lastApiFailureMessage = s.getLastApiFailureMessage();
            if (!emergencyStopped.get()) {
                consecutiveApiFailures = 0;
                lastApiFailureAt = null;
                lastApiFailureMessage = null;
            }
        }
    }

    public boolean isAutoTrading() {
        return autoTrading.get();
    }

    public boolean isEmergencyStopped() {
        return emergencyStopped.get();
    }

    public boolean tryStartDecision() {
        return deciding.compareAndSet(false, true);
    }

    public void finishDecision() {
        deciding.set(false);
    }

    public boolean isDeciding() {
        return deciding.get();
    }

    public synchronized void setAutoTrading(boolean enabled) {
        autoTrading.set(enabled);
        if (enabled) {
            emergencyStopped.set(false);
            apiFailureCounts.clear();
            consecutiveApiFailures = 0;
            lastApiFailureAt = null;
            lastApiFailureMessage = null;
        }
        save();
    }

    public synchronized void emergencyStop(String message) {
        autoTrading.set(false);
        emergencyStopped.set(true);
        lastApiFailureMessage = message;
        save();
    }

    public synchronized boolean recordApiFailure(String apiId, String message, int limit) {
        int endpointFailures = apiFailureCounts.merge(apiId, 1, Integer::sum);
        consecutiveApiFailures =
                apiFailureCounts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        lastApiFailureAt = LocalDateTime.now();
        lastApiFailureMessage = trim(message);
        if (endpointFailures >= limit) {
            autoTrading.set(false);
            emergencyStopped.set(true);
        }
        save();
        return emergencyStopped.get();
    }

    public synchronized void recordApiSuccess(String apiId) {
        if (apiFailureCounts.remove(apiId) == null) return;
        consecutiveApiFailures =
                apiFailureCounts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        if (consecutiveApiFailures == 0) {
            lastApiFailureAt = null;
            lastApiFailureMessage = null;
        }
        save();
    }

    public synchronized boolean checkDailyLoss(double totalAssetUsd, double limitPercent) {
        var s = entity();
        var today = KiwoomUsMarketHours.today();
        if (!today.equals(s.getDailyLossDate())) {
            s.setDailyLossDate(today);
            s.setDailyLossBaseAssetUsd(totalAssetUsd);
            s.setDailyLossTriggered(false);
        }
        double base = s.getDailyLossBaseAssetUsd();
        if (limitPercent > 0 && base > 0 && (base - totalAssetUsd) * 100 / base >= limitPercent)
            s.setDailyLossTriggered(true);
        repository.save(s);
        return s.isDailyLossTriggered();
    }

    public boolean isDailyLossTriggered() {
        var s = entity();
        return KiwoomUsMarketHours.today().equals(s.getDailyLossDate()) && s.isDailyLossTriggered();
    }

    public int getConsecutiveApiFailures() {
        return consecutiveApiFailures;
    }

    public LocalDateTime getLastApiFailureAt() {
        return lastApiFailureAt;
    }

    public String getLastApiFailureMessage() {
        return lastApiFailureMessage;
    }

    private KiwoomUsControlState entity() {
        return repository.findById(1L).orElseGet(KiwoomUsControlState::new);
    }

    private void save() {
        var s = entity();
        s.setAutoTradingEnabled(autoTrading.get());
        s.setEmergencyStopped(emergencyStopped.get());
        s.setConsecutiveApiFailures(consecutiveApiFailures);
        s.setLastApiFailureAt(lastApiFailureAt);
        s.setLastApiFailureMessage(lastApiFailureMessage);
        repository.save(s);
    }

    private String trim(String v) {
        return v == null ? "unknown" : v.substring(0, Math.min(500, v.length()));
    }
}
