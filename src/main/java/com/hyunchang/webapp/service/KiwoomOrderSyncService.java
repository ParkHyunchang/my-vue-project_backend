package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.entity.KiwoomTradeProposal;
import com.hyunchang.webapp.repository.KiwoomTradeProposalRepository;
import com.hyunchang.webapp.util.KiwoomMarketHours;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Reconciles submitted domestic-stock orders with Kiwoom's unfilled/filled order inquiries. */
@Service
public class KiwoomOrderSyncService {
    private static final Logger log = LoggerFactory.getLogger(KiwoomOrderSyncService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Duration URGENT_CANCEL_POLL_WINDOW = Duration.ofSeconds(60);
    private static final Duration APPLICATION_RESTART_LOG_WINDOW = Duration.ofMinutes(5);
    private static final Duration CANCEL_DISAPPEARANCE_GRACE = Duration.ofSeconds(10);
    private static final int CANCEL_DISAPPEARANCE_CONFIRMATIONS = 2;
    private static final List<KiwoomTradeProposal.Status> PRE_MARKET_OPEN_STATUSES =
            List.of(
                    KiwoomTradeProposal.Status.PROPOSED,
                    KiwoomTradeProposal.Status.APPROVED,
                    KiwoomTradeProposal.Status.ORDER_DRAFT,
                    KiwoomTradeProposal.Status.ORDERED,
                    KiwoomTradeProposal.Status.PARTIALLY_FILLED,
                    KiwoomTradeProposal.Status.CANCEL_REQUESTED);
    private final KiwoomTradeService trade;
    private final KiwoomTradeProposalRepository proposals;
    private final KiwoomStrategyAuditService audit;
    private final KiwoomProperties props;
    private final KiwoomPositionExitService exits;
    private final AtomicBoolean syncInProgress = new AtomicBoolean();
    private final Set<String> urgentCancelDelayLogged = ConcurrentHashMap.newKeySet();
    private final java.util.Map<String, Integer> cancelDisappearanceCounts =
            new ConcurrentHashMap<>();
    private final LocalDateTime serviceStartedAt = LocalDateTime.now(KST);
    private volatile LocalDate preMarketRecoveryCompletedDate;
    private volatile LocalDate marketOpenExitStartedDate;

    public KiwoomOrderSyncService(
            KiwoomTradeService trade,
            KiwoomTradeProposalRepository proposals,
            KiwoomStrategyAuditService audit,
            KiwoomProperties props,
            KiwoomPositionExitService exits) {
        this.trade = trade;
        this.proposals = proposals;
        this.audit = audit;
        this.props = props;
        this.exits = exits;
    }

    @Scheduled(fixedDelay = 60000)
    public void scheduledSync() {
        if (props.isConfigured() && KiwoomMarketHours.isOpen() && hasPendingOrders()) sync();
    }

    /** 새 매수 체결 뒤 익절·손절 관리 시작이 1분 늦어지지 않도록 장중 매수 주문만 빠르게 확인한다. */
    @Scheduled(fixedDelay = 3000, initialDelay = 3000)
    public void fastPendingBuySync() {
        if (props.isConfigured() && KiwoomMarketHours.isOpen() && hasPendingBuyOrders()) sync();
    }

    /** 정규장 종료 뒤에는 반복 조회하지 않고 한 번만 최종 체결 상태를 확인한다. */
    @Scheduled(cron = "0 36 15 * * MON-FRI", zone = "Asia/Seoul")
    public void finalMarketCloseSync() {
        LocalDate today = LocalDate.now(KST);
        if (!props.isConfigured() || !KiwoomMarketHours.isTradingDay(today) || !hasPendingOrders())
            return;
        SyncResult result = sync();
        log.info(
                "[자동매매][장 마감 최종 주문 확인] 조회={}건, 갱신={}건, 결과={}",
                result.records(),
                result.updated(),
                result.message());
    }

    /** 08:50부터 전일 주문 정리·포지션 준비가 성공할 때까지 매분 재시도한다. 이 단계에서는 실제 주문을 보내지 않는다. */
    @Scheduled(cron = "0 50-59 8 * * MON-FRI", zone = "Asia/Seoul")
    public void scheduledPreMarketRecovery() {
        LocalDate today = LocalDate.now(KST);
        if (!KiwoomMarketHours.isTradingDay(today)
                || !props.isConfigured()
                || !exits.isExitManagementEnabled()
                || today.equals(preMarketRecoveryCompletedDate)) return;
        runPreMarketRecovery(
                today, recoveryTrigger("08:50 정기 전일 주문 정리", "PRE_MARKET_0850"));
    }

    /** 09:00에 시작하고, 자동매매가 늦게 켜지거나 조회가 실패하면 장중 매분 재시도한다. */
    @Scheduled(cron = "0 * 9-15 * * MON-FRI", zone = "Asia/Seoul")
    public void scheduledMarketOpenExitStart() {
        LocalDate today = LocalDate.now(KST);
        if (!KiwoomMarketHours.isTradingDay(today)
                || !KiwoomMarketHours.isOpen()
                || !props.isConfigured()
                || !exits.isExitManagementEnabled()
                || today.equals(marketOpenExitStartedDate)) return;
        RecoveryTrigger recoveryTrigger =
                recoveryTrigger("장중 재확인", "MARKET_OPEN_RECHECK");
        boolean recoveredNow = !today.equals(preMarketRecoveryCompletedDate);
        if (recoveredNow) {
            RecoveryOutcome outcome = runPreMarketRecovery(today, recoveryTrigger);
            // 다른 동기화가 락을 쥐고 있어 비켜준 것뿐이면 다음 분 크론이 그대로 재시도한다.
            if (outcome == RecoveryOutcome.BUSY) return;
            if (outcome != RecoveryOutcome.COMPLETED) {
                log.error(
                        "[자동매매][장 시작 주문 보류] 전일 주문 정리가 완료되지 않아 익절 주문 시작을 보류합니다. 키움 연결과 주문 상태를 확인해 주세요.");
                return;
            }
        }
        boolean recentApplicationRestart = isRecentApplicationRestart();
        String startReason =
                recentApplicationRestart
                        ? "서버 재기동 후 확인"
                        : recoveredNow
                                ? recoveryTrigger.label()
                                : "09:00 정규장 시작";
        String refreshSource =
                recentApplicationRestart
                        ? "APPLICATION_RESTART"
                        : recoveredNow ? recoveryTrigger.refreshSource() : "MARKET_OPEN_0900";
        if (exits.startExitOrdersAtMarketOpen(startReason, refreshSource))
            marketOpenExitStartedDate = today;
    }

    private RecoveryOutcome runPreMarketRecovery(LocalDate today, RecoveryTrigger trigger) {
        PreMarketRecoveryResult result = reconcilePreviousDayOrders(today);
        if (result.busy()) {
            // 정상적인 락 양보다. 진짜 실패와 섞이면 로그에서 장애를 가려버린다.
            log.info(
                    "[자동매매][전일 주문 정리 대기] 실행={}, 사유=다른 주문 상태 동기화가 진행 중, 처리=다음 분에 다시 시도",
                    trigger.label());
            return RecoveryOutcome.BUSY;
        }
        if (!result.success()) {
            log.warn(
                    "[자동매매][전일 주문 정리 재시도 대기] 실행={}, 사유={}",
                    trigger.label(),
                    result.message());
            return RecoveryOutcome.FAILED;
        }
        if (!exits.preparePositionsBeforeMarketOpen(trigger.label(), trigger.refreshSource())) {
            log.warn(
                    "[자동매매][포지션 준비 재시도 대기] 실행={}, 사유=보유 종목 조회 또는 실시간 구독 준비 실패",
                    trigger.label());
            return RecoveryOutcome.FAILED;
        }
        preMarketRecoveryCompletedDate = today;
        String nextAction =
                KiwoomMarketHours.isOpen()
                        ? "현재 장중 청산 관리 시작 준비"
                        : "09:00 주문 시작 대기";
        log.info(
                "[자동매매][전일 주문 정리·포지션 준비 완료] 실행={}, 기준일={}, 키움 주문 응답={}건, 전일 미종결 주문 만료={}건, 처리={}",
                trigger.label(),
                today,
                result.records(),
                result.expired(),
                nextAction);
        audit.log(
                "PREVIOUS_DAY_ORDER_CLEANUP_COMPLETED",
                null,
                trigger.label()
                        + ": 전일 미종결 주문 "
                        + result.expired()
                        + "건을 정리하고 "
                        + nextAction
                        + "를 완료했습니다.");
        return RecoveryOutcome.COMPLETED;
    }

    private RecoveryTrigger recoveryTrigger(String scheduledLabel, String scheduledSource) {
        if (isRecentApplicationRestart())
            return new RecoveryTrigger("서버 재기동 후 확인", "APPLICATION_RESTART");
        return new RecoveryTrigger(scheduledLabel, scheduledSource);
    }

    private boolean isRecentApplicationRestart() {
        Duration elapsed = Duration.between(serviceStartedAt, LocalDateTime.now(KST));
        return !elapsed.isNegative() && elapsed.compareTo(APPLICATION_RESTART_LOG_WINDOW) <= 0;
    }

    PreMarketRecoveryResult reconcilePreviousDayOrders(LocalDate today) {
        if (!syncInProgress.compareAndSet(false, true)) return PreMarketRecoveryResult.lockBusy();
        try {
            List<JsonNode> unfilledRecords = new ArrayList<>();
            List<JsonNode> allRecords = new ArrayList<>();
            collectRecords(
                    trade.getUnfilledOrders().block(Duration.ofSeconds(15)), unfilledRecords);
            allRecords.addAll(unfilledRecords);
            collectRecords(trade.getFilledOrders().block(Duration.ofSeconds(15)), allRecords);
            for (JsonNode record : allRecords) apply(record);

            Set<String> brokerUnfilledOrderNumbers = orderNumbers(unfilledRecords);

            int expired = 0;
            for (KiwoomTradeProposal proposal :
                    proposals.findByStatusIn(PRE_MARKET_OPEN_STATUSES)) {
                if (!isFromPreviousDate(proposal, today) || proposal.getBrokerOrderNo() == null)
                    continue;
                String orderNo = normalizeOrderNo(proposal.getBrokerOrderNo());
                if (orderNo != null && brokerUnfilledOrderNumbers.contains(orderNo)) continue;

                String reason =
                        "전일 일일 주문이 다음 거래일 키움 미체결 목록에 없어 자동 만료 처리했습니다.";
                proposal.expired(reason);
                proposals.save(proposal);
                audit.log("PREVIOUS_DAY_ORDER_EXPIRED", proposal.getId(), reason);
                log.info(
                        "[자동매매][전일 주문 만료] {} {}({}), 주문번호={}, 주문 종류={}, 기존 열린 상태 종료",
                        actionLabel(proposal.getAction()),
                        proposal.getStockName(),
                        proposal.getStockCode(),
                        proposal.getBrokerOrderNo(),
                        isTakeProfit(proposal) ? "익절 지정가" : "일반 주문");
                expired++;
            }
            return PreMarketRecoveryResult.completed(
                    allRecords.size(), expired, "전일 주문 정리 완료");
        } catch (Exception e) {
            return PreMarketRecoveryResult.failed(
                    "전일 주문 정리 실패: " + trim(e.getMessage()));
        } finally {
            syncInProgress.set(false);
        }
    }

    /**
     * 손절가가 감지되어 기존 익절 주문을 취소하는 동안에만 키움 주문 상태를 빠르게 확인한다. 일반 주문은 기존 1분 동기화를 사용하므로 불필요한 API 호출을 늘리지
     * 않는다.
     */
    @Scheduled(fixedDelay = 2000, initialDelay = 2000)
    public void urgentStopCancellationSync() {
        if (!props.isConfigured() || !KiwoomMarketHours.isOpen()) return;

        List<KiwoomTradeProposal> cancelRequests =
                proposals.findByStatusIn(List.of(KiwoomTradeProposal.Status.CANCEL_REQUESTED));
        Set<String> activeKeys = ConcurrentHashMap.newKeySet();
        LocalDateTime now = LocalDateTime.now();
        boolean needsUrgentSync = false;

        for (KiwoomTradeProposal proposal : cancelRequests) {
            if (!isTakeProfit(proposal) || !exits.isStopTransitionPending(proposal.getStockCode()))
                continue;

            String key = urgentCancelKey(proposal);
            activeKeys.add(key);
            LocalDateTime requestedAt = proposal.getCancelRequestedAt();
            boolean insideFastWindow =
                    requestedAt == null
                            || Duration.between(requestedAt, now)
                                            .compareTo(URGENT_CANCEL_POLL_WINDOW)
                                    <= 0;
            if (insideFastWindow) {
                needsUrgentSync = true;
            } else if (urgentCancelDelayLogged.add(key)) {
                log.warn(
                        "[자동매매][청산 전환 지연] {}({}), 익절 주문번호={}, 60초 동안 취소 확인이 되지 않아 일반 주문 동기화로 계속 확인합니다. 키움 주문 상태를 확인해 주세요.",
                        proposal.getStockName(),
                        proposal.getStockCode(),
                        proposal.getBrokerOrderNo());
                audit.log(
                        "STOP_CANCEL_CONFIRMATION_DELAYED",
                        proposal.getId(),
                        "손절·수동 청산 전환을 위한 익절 주문 취소가 60초 안에 확인되지 않았습니다.");
            }
        }
        urgentCancelDelayLogged.retainAll(activeKeys);
        if (needsUrgentSync) sync();
    }

    /** 재시작 직후, 새 스케줄 판단이 돌기 전에 저장된 주문번호들을 키움 조회로 복구 동기화한다. */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverAfterRestart() {
        if (props.isConfigured() && hasPendingOrders()) {
            SyncResult result = sync();
            audit.log(
                    "ORDER_RECOVERY_SYNC",
                    null,
                    "재시작 복구 동기화: " + result.message() + " (갱신 " + result.updated() + "건)");
        }
    }

    public SyncResult sync() {
        if (!syncInProgress.compareAndSet(false, true))
            return new SyncResult(0, 0, "다른 주문 상태 동기화가 진행 중입니다.");
        SyncResult result;
        try {
            result = doSync();
        } finally {
            syncInProgress.set(false);
        }
        // 익절 주문 재등록은 브로커 조회 락 밖에서 한다. 여러 건을 다시 거는 동안 락을 쥐고 있으면
        // 2초 주기 손절 취소 확인과 장 시작 사전 복구가 그만큼 밀린다. 청산 작업 자체는
        // refreshPositions가 synchronized라 그대로 직렬로 돈다.
        if (result.updated() > 0) exits.onOrderStateChanged();
        return result;
    }

    private SyncResult doSync() {
        if (!hasPendingOrders()) return new SyncResult(0, 0, "동기화 대상 주문이 없습니다.");
        try {
            List<JsonNode> unfilledRecords = new ArrayList<>();
            List<JsonNode> records = new ArrayList<>();
            collectRecords(
                    trade.getUnfilledOrders().block(Duration.ofSeconds(15)), unfilledRecords);
            records.addAll(unfilledRecords);
            collectRecords(trade.getFilledOrders().block(Duration.ofSeconds(15)), records);
            int updated = 0;
            for (JsonNode record : records) if (apply(record)) updated++;
            Set<String> unfilledOrderNumbers = orderNumbers(unfilledRecords);
            for (KiwoomTradeProposal proposal :
                    proposals.findByStatusIn(
                            List.of(KiwoomTradeProposal.Status.CANCEL_REQUESTED))) {
                boolean explicitlyConfirmed =
                        records.stream()
                                .anyMatch(record -> isCancellationConfirmation(record, proposal));
                boolean goneFromBroker =
                        !explicitlyConfirmed && isGoneFromUnfilledOrders(proposal, unfilledOrderNumbers);
                if (explicitlyConfirmed || goneFromBroker) {
                    String evidence =
                            explicitlyConfirmed
                                    ? "키움 주문상태·주문구분 응답으로 취소가 명시적으로 확인되었습니다."
                                    : "취소 요청 뒤 키움 미체결 목록에서 연속 "
                                            + CANCEL_DISAPPEARANCE_CONFIRMATIONS
                                            + "회 사라져 취소로 확정했습니다.";
                    proposal.cancelled();
                    proposals.save(proposal);
                    forgetCancelDisappearance(proposal);
                    audit.log("ORDER_CANCELLED_SYNC", proposal.getId(), evidence);
                    log.info(
                            "[자동매매][주문 취소 확인] {} {}({}), 원주문번호={}, 상태=취소 완료, 확인 근거={}",
                            actionLabel(proposal.getAction()),
                            proposal.getStockName(),
                            proposal.getStockCode(),
                            proposal.getBrokerOrderNo(),
                            explicitlyConfirmed ? "키움 취소 응답" : "미체결 목록에서 사라짐");
                    updated++;
                }
            }
            return new SyncResult(records.size(), updated, "주문 상태 동기화 완료");
        } catch (Exception e) {
            return new SyncResult(0, 0, "주문 상태 동기화 실패: " + trim(e.getMessage()));
        }
    }

    private boolean apply(JsonNode record) {
        String orderNo = text(record, "ord_no", "order_no");
        if (orderNo == null || orderNo.isBlank()) return false;
        return proposals
                .findByBrokerOrderNo(orderNo)
                .map(
                        proposal -> {
                            // 취소 확인 레코드에는 잔량 0이 함께 내려올 수 있다. 이를 일반 체결로
                            // 처리하면 CANCEL_REQUESTED가 다시 ORDERED로 바뀔 수 있으므로 취소
                            // 상태 동기화 전용 경로에 맡긴다.
                            if (isCancellationConfirmation(record, proposal)) return false;
                            Long orderedValue = longValue(record, "ord_qty", "order_qty", "qty");
                            Long filledValue =
                                    longValue(record, "cntr_qty", "filled_qty", "exec_qty");
                            Long remainingValue =
                                    longValue(
                                            record,
                                            "rmn_qty",
                                            "unfilled_qty",
                                            "ord_remain_qty",
                                            "oso_qty");

                            // A missing field is not the same as zero.  In particular, treating an
                            // omitted remaining quantity as zero incorrectly changed live limit
                            // orders
                            // into FILLED orders.
                            if (filledValue == null && remainingValue == null) return false;

                            int ordered =
                                    orderedValue == null
                                            ? proposal.getQuantity()
                                            : Math.max(0, orderedValue.intValue());
                            int filled;
                            int remaining;
                            if (remainingValue != null) {
                                remaining = Math.max(0, remainingValue.intValue());
                                filled =
                                        filledValue == null
                                                ? Math.max(0, ordered - remaining)
                                                : Math.max(0, filledValue.intValue());
                            } else {
                                // A positive explicit execution quantity can advance the state.  Do
                                // not infer any execution from an omitted field.
                                filled =
                                        Math.max(
                                                proposal.getFilledQuantity(),
                                                Math.max(0, filledValue.intValue()));
                                remaining = Math.max(0, proposal.getQuantity() - filled);
                            }
                            Long price =
                                    longValue(
                                            record,
                                            "cntr_prc",
                                            "cntr_pric",
                                            "avg_cntr_prc",
                                            "filled_price");
                            KiwoomTradeProposal.Status before = proposal.getStatus();
                            int beforeFilled = proposal.getFilledQuantity();
                            int beforeRemaining = proposal.getRemainingQuantity();
                            Long beforePrice = proposal.getAverageFillPrice();
                            proposal.syncFill(filled, remaining, price);
                            proposals.save(proposal);
                            boolean changed =
                                    before != proposal.getStatus()
                                            || beforeFilled != proposal.getFilledQuantity()
                                            || beforeRemaining != proposal.getRemainingQuantity()
                                            || !java.util.Objects.equals(
                                                    beforePrice, proposal.getAverageFillPrice());
                            if (before != proposal.getStatus())
                                audit.log(
                                        "ORDER_STATUS_SYNC",
                                        proposal.getId(),
                                        "키움 주문 상태가 " + proposal.getStatus() + "로 변경되었습니다.");
                            if (changed)
                                log.info(
                                        "[자동매매][{}] {} {}({}), 체결 {}/{}주, 평균 체결가={}원, 주문번호={}, 주문 근거={}",
                                        orderSyncLabel(proposal),
                                        actionLabel(proposal.getAction()),
                                        proposal.getStockName(),
                                        proposal.getStockCode(),
                                        proposal.getFilledQuantity(),
                                        proposal.getQuantity(),
                                        proposal.getAverageFillPrice() == null
                                                ? "미확인"
                                                : String.format(
                                                        "%,d", proposal.getAverageFillPrice()),
                                        orderNo,
                                        proposal.getReason());
                            return changed;
                        })
                .orElse(false);
    }

    private boolean isCancellationConfirmation(JsonNode record, KiwoomTradeProposal proposal) {
        String brokerOrderNo = normalizeOrderNo(proposal.getBrokerOrderNo());
        if (brokerOrderNo == null) return false;
        String recordOrderNo = normalizeOrderNo(text(record, "ord_no", "order_no"));
        String originalOrderNo =
                normalizeOrderNo(text(record, "orig_ord_no", "org_ord_no", "ori_ord_no"));
        boolean sameOrder =
                brokerOrderNo.equals(recordOrderNo) || brokerOrderNo.equals(originalOrderNo);
        if (!sameOrder) return false;
        for (String field :
                List.of(
                        "ord_stt",
                        "order_status",
                        "io_tp_nm",
                        "trde_tp",
                        "tsk_tp",
                        "mdfy_cncl_tp",
                        "mdfy_cncl",
                        "acpt_tp")) {
            String value = text(record, field);
            if (value == null) continue;
            String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
            if (normalized.contains("취소")
                    || normalized.contains("CANCELLED")
                    || normalized.contains("CANCELED")
                    || normalized.equals("CANCEL")) return true;
        }
        return false;
    }

    /**
     * 키움은 취소가 끝난 주문을 미체결·체결 조회 어디에도 남기지 않는 경우가 있다. 그러면 취소 요청이 영원히 확인되지 않아 손절 전환이 멈추므로, 유예 시간이 지난 뒤
     * 미체결 목록에서 연속으로 사라진 것을 확인하면 취소로 확정한다. 조회 한 번을 놓친 것과 구분하려고 연속 확인을 요구한다. 실제 주문이 아직 살아 있었더라도 키움
     * 잔고의 매도가능수량이 0이라 청산 주문은 나가지 않으므로 이중 매도로 이어지지 않는다.
     */
    private boolean isGoneFromUnfilledOrders(
            KiwoomTradeProposal proposal, Set<String> unfilledOrderNumbers) {
        String orderNo = normalizeOrderNo(proposal.getBrokerOrderNo());
        if (orderNo == null) return false;
        LocalDateTime requestedAt = proposal.getCancelRequestedAt();
        if (requestedAt == null
                || Duration.between(requestedAt, LocalDateTime.now())
                                .compareTo(CANCEL_DISAPPEARANCE_GRACE)
                        < 0) return false;
        if (unfilledOrderNumbers.contains(orderNo)) {
            cancelDisappearanceCounts.remove(orderNo);
            return false;
        }
        return cancelDisappearanceCounts.merge(orderNo, 1, Integer::sum)
                >= CANCEL_DISAPPEARANCE_CONFIRMATIONS;
    }

    private void forgetCancelDisappearance(KiwoomTradeProposal proposal) {
        String orderNo = normalizeOrderNo(proposal.getBrokerOrderNo());
        if (orderNo != null) cancelDisappearanceCounts.remove(orderNo);
    }

    private Set<String> orderNumbers(List<JsonNode> records) {
        Set<String> result = ConcurrentHashMap.newKeySet();
        for (JsonNode record : records) {
            String orderNo = normalizeOrderNo(text(record, "ord_no", "order_no"));
            if (orderNo != null) result.add(orderNo);
        }
        return result;
    }

    private String normalizeOrderNo(String value) {
        if (value == null || value.isBlank()) return null;
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.isBlank()) return value.trim();
        return digits.replaceFirst("^0+(?!$)", "");
    }

    private String orderSyncLabel(KiwoomTradeProposal proposal) {
        if (isTakeProfit(proposal))
            return switch (proposal.getStatus()) {
                case FILLED -> "익절 지정가 주문 체결 완료";
                case PARTIALLY_FILLED -> "익절 지정가 주문 일부 체결";
                case ORDERED -> "익절 지정가 주문 미체결";
                default -> "익절 지정가 주문 상태 변경";
            };
        if (hasExitReason(proposal, "[EXIT:STOP_LOSS]"))
            return switch (proposal.getStatus()) {
                case FILLED -> "손절 시장가 주문 체결 완료";
                case PARTIALLY_FILLED -> "손절 시장가 주문 일부 체결";
                case ORDERED -> "손절 시장가 주문 접수";
                default -> "손절 시장가 주문 상태 변경";
            };
        if (hasExitReason(proposal, "[EXIT:TIME_EXIT]"))
            return switch (proposal.getStatus()) {
                case FILLED -> "보유기간 청산 주문 체결 완료";
                case PARTIALLY_FILLED -> "보유기간 청산 주문 일부 체결";
                case ORDERED -> "보유기간 청산 주문 접수";
                default -> "보유기간 청산 주문 상태 변경";
            };
        return statusLabel(proposal.getStatus());
    }

    private String actionLabel(KiwoomTradeProposal.Action action) {
        return action == KiwoomTradeProposal.Action.BUY ? "매수" : "매도";
    }

    private String statusLabel(KiwoomTradeProposal.Status status) {
        return switch (status) {
            case FILLED -> "전량 체결";
            case PARTIALLY_FILLED -> "일부 체결";
            case CANCELED -> "주문 취소";
            case ORDERED -> "주문 접수";
            default -> "주문 상태 변경";
        };
    }

    private boolean hasPendingOrders() {
        return !proposals
                .findByStatusIn(
                        List.of(
                                KiwoomTradeProposal.Status.ORDERED,
                                KiwoomTradeProposal.Status.PARTIALLY_FILLED,
                                KiwoomTradeProposal.Status.CANCEL_REQUESTED))
                .isEmpty();
    }

    private boolean hasPendingBuyOrders() {
        return proposals
                .findByStatusIn(
                        List.of(
                                KiwoomTradeProposal.Status.ORDERED,
                                KiwoomTradeProposal.Status.PARTIALLY_FILLED))
                .stream()
                .anyMatch(
                        proposal -> proposal.getAction() == KiwoomTradeProposal.Action.BUY);
    }

    private boolean isTakeProfit(KiwoomTradeProposal proposal) {
        return hasExitReason(proposal, "[EXIT:TAKE_PROFIT");
    }

    private boolean hasExitReason(KiwoomTradeProposal proposal, String prefix) {
        return proposal.getReason() != null && proposal.getReason().startsWith(prefix);
    }

    private boolean isFromPreviousDate(KiwoomTradeProposal proposal, LocalDate today) {
        LocalDateTime reference = proposal.getOrderedAt();
        if (reference == null) reference = proposal.getDraftedAt();
        if (reference == null) reference = proposal.getApprovedAt();
        if (reference == null) reference = proposal.getCreatedAt();
        return reference != null && reference.toLocalDate().isBefore(today);
    }

    private String urgentCancelKey(KiwoomTradeProposal proposal) {
        if (proposal.getId() != null) return "id:" + proposal.getId();
        return "order:" + proposal.getBrokerOrderNo() + ":" + proposal.getStockCode();
    }

    private void collectRecords(JsonNode node, List<JsonNode> result) {
        if (node == null) return;
        if (node.isArray()) {
            for (JsonNode child : node) collectRecords(child, result);
            return;
        }
        if (node.isObject()) {
            if (node.has("ord_no") || node.has("order_no")) result.add(node);
            Iterator<JsonNode> children = node.elements();
            while (children.hasNext()) collectRecords(children.next(), result);
        }
    }

    private String text(JsonNode node, String... fields) {
        for (String field : fields) if (node.hasNonNull(field)) return node.path(field).asText();
        return null;
    }

    private Long longValue(JsonNode node, String... fields) {
        for (String field : fields)
            if (node.hasNonNull(field)) {
                String raw = node.path(field).asText().replace(",", "").trim();
                try {
                    return Long.parseLong(raw);
                } catch (NumberFormatException ignored) {
                }
            }
        return null;
    }

    private String trim(String value) {
        return value == null ? "unknown" : value.substring(0, Math.min(500, value.length()));
    }

    public record SyncResult(int records, int updated, String message) {}

    record PreMarketRecoveryResult(
            boolean success, boolean busy, int records, int expired, String message) {
        /** 다른 동기화가 락을 쥐고 있어 이번 차례를 건너뛴 것 — 재시도로 풀리는 정상 상황이다. */
        static PreMarketRecoveryResult lockBusy() {
            return new PreMarketRecoveryResult(
                    false, true, 0, 0, "다른 주문 상태 동기화가 진행 중입니다.");
        }

        static PreMarketRecoveryResult failed(String message) {
            return new PreMarketRecoveryResult(false, false, 0, 0, message);
        }

        static PreMarketRecoveryResult completed(int records, int expired, String message) {
            return new PreMarketRecoveryResult(true, false, records, expired, message);
        }
    }

    /** 사전 복구가 끝났는지, 락 때문에 비켜준 것인지, 진짜 실패인지 구분한다. */
    private enum RecoveryOutcome {
        COMPLETED,
        BUSY,
        FAILED
    }

    private record RecoveryTrigger(String label, String refreshSource) {}
}
