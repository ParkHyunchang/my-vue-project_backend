package com.hyunchang.webapp.service;

import com.hyunchang.webapp.entity.KiwoomStrategyControlState;
import com.hyunchang.webapp.repository.KiwoomStrategyControlStateRepository;
import com.hyunchang.webapp.util.KiwoomMarketHours;
import jakarta.annotation.PostConstruct;
import java.time.LocalDate;
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
    private volatile DailyLossStatus dailyLoss;

    public KiwoomAutoTradeState(KiwoomStrategyControlStateRepository controlStateRepository) {
        this.controlStateRepository = controlStateRepository;
    }

    @PostConstruct
    void restoreControlState() {
        KiwoomStrategyControlState saved = controlStateRepository.findById(1L).orElse(null);
        if (saved == null) return;
        emergencyStopped.set(saved.isEmergencyStopped());
        if (saved.getDailyLossSnapshotDate() != null) {
            dailyLoss =
                    new DailyLossStatus(
                            saved.getDailyLossSnapshotDate(),
                            saved.getDailyLossBaseAsset(),
                            saved.getDailyLossLastAsset(),
                            saved.isDailyLossTriggered(),
                            saved.getDailyLossLastCheckedAt());
        }
    }

    public boolean isAutoTrading() {
        return autoTrading.get();
    }

    public void setAutoTrading(boolean enabled) {
        autoTrading.set(enabled);
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

    public boolean isEmergencyStopped() {
        return emergencyStopped.get();
    }

    public void emergencyStop() {
        autoTrading.set(false);
        emergencyStopped.set(true);
        persistEmergencyStop(true);
    }

    public void clearEmergencyStop() {
        emergencyStopped.set(false);
        persistEmergencyStop(false);
    }

    private void persistEmergencyStop(boolean stopped) {
        KiwoomStrategyControlState entity =
                controlStateRepository.findById(1L).orElseGet(KiwoomStrategyControlState::new);
        entity.setEmergencyStopped(stopped);
        controlStateRepository.save(entity);
    }

    public LocalDateTime getLastRunAt() {
        return lastRunAt;
    }

    public void markRun() {
        lastRunAt = LocalDateTime.now();
    }

    /**
     * 총자산 체크 1회를 기록한다. 저장된 스냅샷 날짜가 오늘(KST)이 아니면 오늘 기준으로 새 스냅샷을 잡고 발동 상태를 리셋한다.
     *
     * @return 이번 체크로 한도가 새로 발동됐는지 (이미 발동 중이면 false)
     */
    public synchronized boolean recordDailyLossCheck(long totalAsset, long limitAmount) {
        LocalDate today = LocalDate.now(KiwoomMarketHours.KST);
        DailyLossStatus prev = dailyLoss;
        boolean sameDay = prev != null && today.equals(prev.snapshotDate());
        long base = sameDay ? prev.baseAsset() : totalAsset;
        boolean wasTriggered = sameDay && prev.triggered();
        boolean triggered = wasTriggered || (limitAmount > 0 && base - totalAsset >= limitAmount);
        DailyLossStatus next =
                new DailyLossStatus(today, base, totalAsset, triggered, LocalDateTime.now());
        dailyLoss = next;
        persistDailyLoss(next);
        return triggered && !wasTriggered;
    }

    /** 오늘 발동 여부. 스냅샷 날짜가 지난 상태면 쓰기 없이 자동으로 해제된 것으로 본다. */
    public boolean isDailyLossTriggered() {
        DailyLossStatus s = dailyLoss;
        return s != null
                && s.triggered()
                && LocalDate.now(KiwoomMarketHours.KST).equals(s.snapshotDate());
    }

    /** 마지막 체크 상태. 아직 한 번도 체크하지 않았으면 null. */
    public DailyLossStatus dailyLossStatus() {
        return dailyLoss;
    }

    private void persistDailyLoss(DailyLossStatus s) {
        KiwoomStrategyControlState entity =
                controlStateRepository.findById(1L).orElseGet(KiwoomStrategyControlState::new);
        entity.setDailyLossSnapshotDate(s.snapshotDate());
        entity.setDailyLossBaseAsset(s.baseAsset());
        entity.setDailyLossLastAsset(s.lastAsset());
        entity.setDailyLossTriggered(s.triggered());
        entity.setDailyLossLastCheckedAt(s.lastCheckedAt());
        controlStateRepository.save(entity);
    }

    public record DailyLossStatus(
            LocalDate snapshotDate,
            long baseAsset,
            long lastAsset,
            boolean triggered,
            LocalDateTime lastCheckedAt) {
        public long drawdown() {
            return Math.max(0, baseAsset - lastAsset);
        }
    }
}
