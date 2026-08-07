package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.service.kiwoom.KiwoomAutoTradeState;
import com.hyunchang.webapp.service.kiwoom.KiwoomWebsocketClient;
import com.hyunchang.webapp.util.KiwoomMarketHours;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 일일 손실 안전장치를 주기적으로 확인한다. 손절·익절·최대 보유기간 청산은 KiwoomPositionExitService가 단독으로 담당해 같은 종목의 중복 매도를 방지한다.
 */
@Service
public class KiwoomRiskManagerService {
    private static final Logger log = LoggerFactory.getLogger(KiwoomRiskManagerService.class);

    private final KiwoomProperties props;
    private final KiwoomTradeService trade;
    private final KiwoomAutoTradeState state;
    private final KiwoomStrategySettingsService settings;
    private final KiwoomStrategyAuditService audit;
    private final KiwoomWebsocketClient events;
    private volatile LocalDateTime lastScanAt;

    public KiwoomRiskManagerService(
            KiwoomProperties props,
            KiwoomTradeService trade,
            KiwoomAutoTradeState state,
            KiwoomStrategySettingsService settings,
            KiwoomStrategyAuditService audit,
            KiwoomWebsocketClient events) {
        this.props = props;
        this.trade = trade;
        this.state = state;
        this.settings = settings;
        this.audit = audit;
        this.events = events;
    }

    /** AI 판단 스케줄(0/30분)·주문 동기화와 겹치지 않도록 2분 오프셋을 두고 5분 간격으로 돈다. */
    @Scheduled(cron = "0 2/5 9-15 * * MON-FRI", zone = "Asia/Seoul")
    public void scheduledRiskScan() {
        if (props.getStrategy().isEnabled()
                && state.isAutoTrading()
                && props.isConfigured()
                && KiwoomMarketHours.isOpen()) {
            try {
                runRiskScan("SCHEDULE");
            } catch (IllegalStateException ignored) {
                // 긴급 중지 또는 AI 판단이 실행 중 — 다음 주기에 다시 시도한다.
            }
        }
    }

    /** AI 판단(runDecision)과 단일 실행 가드를 공유해 동일 계좌 조회가 동시에 실행되지 않게 한다. */
    public RiskScanResult runRiskScan(String by) {
        if (state.isEmergencyStopped())
            throw new IllegalStateException("긴급 중지 상태에서는 리스크 스캔을 실행할 수 없습니다.");
        if (!state.tryStartDecision()) throw new IllegalStateException("이미 전략 판단이 실행 중입니다.");
        try {
            JsonNode depositNode = trade.getDeposit().block(Duration.ofSeconds(10));
            JsonNode balance = trade.getBalance().block(Duration.ofSeconds(10));
            var current = settings.current();

            KiwoomTradeService.AccountAsset accountAsset = trade.accountAsset(depositNode, balance);
            long totalAsset = accountAsset.amount();
            long dailyLossLimit = dailyLossLimit(current.getDailyLossLimitAmount());
            if (state.recordDailyLossCheck(totalAsset, dailyLossLimit)) {
                KiwoomAutoTradeState.DailyLossStatus loss = state.dailyLossStatus();
                String detail = dailyLossTriggerDetail(loss, dailyLossLimit, accountAsset.source());
                log.warn("[자동매매][일일 손실 한도 발동] {}", detail);
                audit.log("DAILY_LOSS_TRIGGERED", null, detail);
                events.publishEvent("strategy", "일일 손실 한도 발동 — " + detail);
            }
            boolean dailyLossTriggered = state.isDailyLossTriggered();
            lastScanAt = LocalDateTime.now();

            if (!current.isRiskLoopEnabled()) {
                return new RiskScanResult(
                        null, 0, 0, dailyLossTriggered, "리스크 루프가 꺼져 있어 일일 손실 체크만 수행했습니다.");
            }

            List<KiwoomTradeService.Holding> holdings = trade.parseHoldings(balance);
            String message = "일일 손실 안전장치를 확인했습니다. 익절·손절·보유기간 청산은 단일 청산 관리자가 처리합니다.";
            return new RiskScanResult(null, holdings.size(), 0, dailyLossTriggered, message);
        } finally {
            state.finishDecision();
        }
    }

    public LocalDateTime getLastScanAt() {
        return lastScanAt;
    }

    /** 관리자가 잘못 발동한 당일 신규 매수 차단을 해제한다. */
    public DailyLossResetResult resetDailyLossGuard() {
        if (!state.tryStartDecision())
            throw new IllegalStateException("다른 자동매매 판단이 실행 중이라 일일 손실 차단을 해제할 수 없습니다.");
        try {
            JsonNode depositNode = trade.getDeposit().block(Duration.ofSeconds(10));
            JsonNode balance = trade.getBalance().block(Duration.ofSeconds(10));
            KiwoomTradeService.AccountAsset accountAsset = trade.accountAsset(depositNode, balance);
            KiwoomAutoTradeState.DailyLossStatus reset =
                    state.resetDailyLossCheck(accountAsset.amount());
            String detail =
                    String.format(
                            "관리자 해제: 새 기준자산=%,d원, 자산 계산=%s",
                            reset.baseAsset(), accountAsset.source());
            log.warn("[자동매매][일일 손실 한도 해제] {}", detail);
            audit.log("DAILY_LOSS_RESET", null, detail);
            events.publishEvent("strategy", "일일 손실 한도 차단을 해제했습니다 — " + detail);
            return new DailyLossResetResult(
                    reset.baseAsset(), accountAsset.source(), reset.lastCheckedAt());
        } finally {
            state.finishDecision();
        }
    }

    /** 저장된 일일 손실 한도가 바뀐 경우 현재 당일 스냅샷에 즉시 다시 적용한다. */
    public String applyChangedDailyLossLimit(long limitAmount) {
        KiwoomAutoTradeState.DailyLossStatus applied =
                state.applyChangedDailyLossLimit(Math.max(0, limitAmount));
        if (applied == null
                || !java.time.LocalDate.now(KiwoomMarketHours.KST).equals(applied.snapshotDate())) {
            String message = "당일 자산 기준점이 없어 다음 계좌 점검부터 새 한도를 적용합니다.";
            log.info("[자동매매][일일 손실 한도 설정 적용] 한도={}원, 처리={}", limitAmount, message);
            return message;
        }
        String message =
                String.format(
                        "기준자산=%,d원, 현재자산=%,d원, 현재손실=%,d원, 새 한도=%,d원, 신규 매수 차단=%s",
                        applied.baseAsset(),
                        applied.lastAsset(),
                        applied.drawdown(),
                        Math.max(0, limitAmount),
                        applied.triggered() ? "유지 또는 발동" : "해제");
        log.info("[자동매매][일일 손실 한도 설정 적용] {}", message);
        audit.log("DAILY_LOSS_LIMIT_UPDATED", null, message);
        return message;
    }

    public long dailyLossLimit(long configuredAmount) {
        return Math.max(0, configuredAmount);
    }

    private String dailyLossTriggerDetail(
            KiwoomAutoTradeState.DailyLossStatus loss, long limitAmount, String assetSource) {
        if (loss == null) return String.format("한도=%,d원, 자산 계산=%s", limitAmount, assetSource);
        return String.format(
                "기준자산=%,d원, 현재자산=%,d원, 손실=%,d원, 한도=%,d원, 자산 계산=%s",
                loss.baseAsset(), loss.lastAsset(), loss.drawdown(), limitAmount, assetSource);
    }

    public record RiskScanResult(
            Long runId,
            int holdingCount,
            int proposalCount,
            boolean dailyLossTriggered,
            String message) {}

    public record DailyLossResetResult(
            long newBaseAsset, String assetSource, LocalDateTime resetAt) {}
}
