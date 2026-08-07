package com.hyunchang.webapp.service.kiwoom;

import com.hyunchang.webapp.entity.KiwoomStrategyControlState;
import com.hyunchang.webapp.repository.KiwoomStrategyControlStateRepository;
import com.hyunchang.webapp.util.KiwoomMarketHours;
import jakarta.annotation.PostConstruct;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/** Persisted automation switch, safety stop, and single-flight guard for live decisions. */
@Component
public class KiwoomAutoTradeState {
    private final KiwoomStrategyControlStateRepository controlStateRepository;
    private final AtomicBoolean autoTrading = new AtomicBoolean(false);
    private final AtomicBoolean deciding = new AtomicBoolean(false);
    private final AtomicBoolean emergencyStopped = new AtomicBoolean(false);
    private volatile LocalDateTime lastRunAt;
    private volatile DailyLossStatus dailyLoss;
    private volatile int consecutiveApiFailures;
    private volatile LocalDateTime lastApiFailureAt;
    private volatile String lastApiFailureMessage;
    private volatile ClosingAsset lastCloseAsset;
    private volatile ClosingAsset previousCloseAsset;

    public KiwoomAutoTradeState(KiwoomStrategyControlStateRepository controlStateRepository) {
        this.controlStateRepository = controlStateRepository;
    }

    @PostConstruct
    void restoreControlState() {
        KiwoomStrategyControlState saved = controlStateRepository.findById(1L).orElse(null);
        if (saved == null) return;
        autoTrading.set(saved.isAutoTradingEnabled());
        emergencyStopped.set(saved.isEmergencyStopped());
        consecutiveApiFailures = saved.getConsecutiveApiFailures();
        lastApiFailureAt = saved.getLastApiFailureAt();
        lastApiFailureMessage = saved.getLastApiFailureMessage();
        if (saved.getDailyLossSnapshotDate() != null) {
            dailyLoss =
                    new DailyLossStatus(
                            saved.getDailyLossSnapshotDate(),
                            saved.getDailyLossBaseAsset(),
                            saved.getDailyLossLastAsset(),
                            saved.isDailyLossTriggered(),
                            saved.getDailyLossLastCheckedAt());
        }
        if (saved.getLastCloseAssetDate() != null)
            lastCloseAsset =
                    new ClosingAsset(saved.getLastCloseAssetDate(), saved.getLastCloseAssetAmount());
        if (saved.getPreviousCloseAssetDate() != null)
            previousCloseAsset =
                    new ClosingAsset(
                            saved.getPreviousCloseAssetDate(), saved.getPreviousCloseAssetAmount());
    }

    public boolean isAutoTrading() {
        return autoTrading.get();
    }

    public void setAutoTrading(boolean enabled) {
        autoTrading.set(enabled);
        KiwoomStrategyControlState entity = control();
        entity.setAutoTradingEnabled(enabled);
        controlStateRepository.save(entity);
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
        KiwoomStrategyControlState entity = control();
        entity.setAutoTradingEnabled(false);
        entity.setEmergencyStopped(true);
        controlStateRepository.save(entity);
    }

    public void clearEmergencyStop() {
        emergencyStopped.set(false);
        KiwoomStrategyControlState entity = control();
        entity.setEmergencyStopped(false);
        controlStateRepository.save(entity);
    }

    public synchronized boolean recordApiFailure(String message, int limit) {
        consecutiveApiFailures++;
        lastApiFailureAt = LocalDateTime.now();
        lastApiFailureMessage = trim(message);
        boolean stop = consecutiveApiFailures >= Math.max(1, limit);
        if (stop) {
            autoTrading.set(false);
            emergencyStopped.set(true);
        }
        persistApiHealth();
        return stop;
    }

    public synchronized void recordApiSuccess() {
        if (consecutiveApiFailures == 0 && lastApiFailureAt == null) return;
        consecutiveApiFailures = 0;
        lastApiFailureAt = null;
        lastApiFailureMessage = null;
        persistApiHealth();
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

    private KiwoomStrategyControlState control() {
        return controlStateRepository.findById(1L).orElseGet(KiwoomStrategyControlState::new);
    }

    private void persistApiHealth() {
        KiwoomStrategyControlState entity = control();
        entity.setAutoTradingEnabled(autoTrading.get());
        entity.setEmergencyStopped(emergencyStopped.get());
        entity.setConsecutiveApiFailures(consecutiveApiFailures);
        entity.setLastApiFailureAt(lastApiFailureAt);
        entity.setLastApiFailureMessage(lastApiFailureMessage);
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

    /**
     * 관리자가 당일 일일 손실 차단을 해제할 때 현재 총자산을 새 기준점으로 저장한다. 이전 기준점만 유지한 채 플래그를 끄면 다음 점검에서 즉시 다시 발동할 수 있으므로,
     * 반드시 기준자산과 현재자산을 함께 초기화한다.
     */
    public synchronized DailyLossStatus resetDailyLossCheck(long totalAsset) {
        DailyLossStatus next =
                new DailyLossStatus(
                        LocalDate.now(KiwoomMarketHours.KST),
                        totalAsset,
                        totalAsset,
                        false,
                        LocalDateTime.now());
        dailyLoss = next;
        persistDailyLoss(next);
        return next;
    }

    /**
     * 관리자가 일일 손실 한도 금액 자체를 변경했을 때 오늘 스냅샷에 새 한도를 즉시 적용한다. 일반 손실 점검은 한 번 발동한 상태를 유지하지만, 관리자가 한도를 0으로
     * 끄거나 상향한 경우에는 명시적인 설정 변경으로 보고 차단 상태도 새 한도에 맞춰 다시 계산한다.
     */
    public synchronized DailyLossStatus applyChangedDailyLossLimit(long limitAmount) {
        DailyLossStatus previous = dailyLoss;
        LocalDate today = LocalDate.now(KiwoomMarketHours.KST);
        if (previous == null || !today.equals(previous.snapshotDate())) return previous;
        boolean triggered = limitAmount > 0 && previous.drawdown() >= limitAmount;
        DailyLossStatus next =
                new DailyLossStatus(
                        previous.snapshotDate(),
                        previous.baseAsset(),
                        previous.lastAsset(),
                        triggered,
                        LocalDateTime.now());
        dailyLoss = next;
        persistDailyLoss(next);
        return next;
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

    /** Stores one official regular-session close for the next session's asset comparison. */
    public synchronized void recordClosingAsset(long totalAsset) {
        LocalDate today = LocalDate.now(KiwoomMarketHours.KST);
        ClosingAsset current = lastCloseAsset;
        if (current != null && today.equals(current.date())) {
            lastCloseAsset = new ClosingAsset(today, totalAsset);
        } else {
            previousCloseAsset = current;
            lastCloseAsset = new ClosingAsset(today, totalAsset);
        }
        KiwoomStrategyControlState entity = control();
        entity.setLastCloseAssetDate(lastCloseAsset.date());
        entity.setLastCloseAssetAmount(lastCloseAsset.amount());
        entity.setPreviousCloseAssetDate(
                previousCloseAsset == null ? null : previousCloseAsset.date());
        entity.setPreviousCloseAssetAmount(
                previousCloseAsset == null ? 0 : previousCloseAsset.amount());
        controlStateRepository.save(entity);
    }

    /** Returns the change from the previous regular-session close when a baseline exists. */
    public AssetChange assetChangeFromPreviousClose(long currentAsset) {
        LocalDate today = LocalDate.now(KiwoomMarketHours.KST);
        ClosingAsset baseline = lastCloseAsset;
        if (baseline != null && today.equals(baseline.date())) baseline = previousCloseAsset;
        if (baseline == null || baseline.amount() <= 0) return null;
        long amount = currentAsset - baseline.amount();
        return new AssetChange(amount, amount * 100.0 / baseline.amount(), baseline.date());
    }

    private void persistDailyLoss(DailyLossStatus s) {
        KiwoomStrategyControlState entity = control();
        entity.setDailyLossSnapshotDate(s.snapshotDate());
        entity.setDailyLossBaseAsset(s.baseAsset());
        entity.setDailyLossLastAsset(s.lastAsset());
        entity.setDailyLossTriggered(s.triggered());
        entity.setDailyLossLastCheckedAt(s.lastCheckedAt());
        controlStateRepository.save(entity);
    }

    private String trim(String value) {
        return value == null ? "unknown" : value.substring(0, Math.min(500, value.length()));
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

    private record ClosingAsset(LocalDate date, long amount) {}

    public record AssetChange(long amount, double percent, LocalDate baselineDate) {}
}
