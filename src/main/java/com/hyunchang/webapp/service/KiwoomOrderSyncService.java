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
        if (props.isConfigured() && hasPendingOrders()) sync();
    }

    /** 08:50부터 사전 복구가 성공할 때까지 매분 재시도한다. 이 단계에서는 실제 주문을 보내지 않는다. */
    @Scheduled(cron = "0 50-59 8 * * MON-FRI", zone = "Asia/Seoul")
    public void scheduledPreMarketRecovery() {
        LocalDate today = LocalDate.now(KST);
        if (!KiwoomMarketHours.isTradingDay(today)
                || !props.isConfigured()
                || !exits.isExitManagementEnabled()
                || today.equals(preMarketRecoveryCompletedDate)) return;
        runPreMarketRecovery(today);
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
        if (!today.equals(preMarketRecoveryCompletedDate) && !runPreMarketRecovery(today)) {
            log.error(
                    "[자동매매][장 시작 주문 보류] 08:50 사전 복구가 완료되지 않아 익절 주문 시작을 보류합니다. 키움 연결과 주문 상태를 확인해 주세요.");
            return;
        }
        if (exits.startExitOrdersAtMarketOpen()) marketOpenExitStartedDate = today;
    }

    private boolean runPreMarketRecovery(LocalDate today) {
        PreMarketRecoveryResult result = reconcilePreviousDayTakeProfitOrders(today);
        if (!result.success()) {
            log.warn("[자동매매][장 시작 사전 복구 재시도 대기] 사유={}", result.message());
            return false;
        }
        if (!exits.preparePositionsBeforeMarketOpen()) {
            log.warn("[자동매매][장 시작 사전 복구 재시도 대기] 사유=보유 종목 조회 또는 실시간 구독 준비 실패");
            return false;
        }
        preMarketRecoveryCompletedDate = today;
        log.info(
                "[자동매매][장 시작 사전 복구 완료] 기준일={}, 키움 주문 응답={}건, 전일 익절 주문 만료={}건, 처리=09:00 주문 시작 대기",
                today,
                result.records(),
                result.expired());
        audit.log(
                "PRE_MARKET_RECOVERY_COMPLETED",
                null,
                "전일 익절 주문 " + result.expired() + "건을 정리하고 09:00 주문 시작을 준비했습니다.");
        return true;
    }

    PreMarketRecoveryResult reconcilePreviousDayTakeProfitOrders(LocalDate today) {
        if (!syncInProgress.compareAndSet(false, true))
            return new PreMarketRecoveryResult(false, 0, 0, "다른 주문 상태 동기화가 진행 중입니다.");
        try {
            List<JsonNode> unfilledRecords = new ArrayList<>();
            List<JsonNode> allRecords = new ArrayList<>();
            collectRecords(
                    trade.getUnfilledOrders().block(Duration.ofSeconds(15)), unfilledRecords);
            allRecords.addAll(unfilledRecords);
            collectRecords(trade.getFilledOrders().block(Duration.ofSeconds(15)), allRecords);
            for (JsonNode record : allRecords) apply(record);

            Set<String> brokerUnfilledOrderNumbers = ConcurrentHashMap.newKeySet();
            for (JsonNode record : unfilledRecords) {
                String orderNo = normalizeOrderNo(text(record, "ord_no", "order_no"));
                if (orderNo != null) brokerUnfilledOrderNumbers.add(orderNo);
            }

            int expired = 0;
            for (KiwoomTradeProposal proposal :
                    proposals.findByStatusIn(PRE_MARKET_OPEN_STATUSES)) {
                if (!isTakeProfit(proposal) || !isFromPreviousDate(proposal, today)) continue;
                String orderNo = normalizeOrderNo(proposal.getBrokerOrderNo());
                if (orderNo != null && brokerUnfilledOrderNumbers.contains(orderNo)) continue;

                String reason = "전일 미체결 익절 주문이 다음 거래일 키움 미체결 목록에 없어 자동 만료 처리했습니다.";
                proposal.expired(reason);
                proposals.save(proposal);
                audit.log("TAKE_PROFIT_ORDER_EXPIRED", proposal.getId(), reason);
                log.info(
                        "[자동매매][전일 익절 주문 만료] {}({}), 주문번호={}, 기존 상태 종료 후 09:00 재등록 대상",
                        proposal.getStockName(),
                        proposal.getStockCode(),
                        proposal.getBrokerOrderNo() == null ? "미전송" : proposal.getBrokerOrderNo());
                expired++;
            }
            return new PreMarketRecoveryResult(
                    true, allRecords.size(), expired, "장 시작 사전 주문 복구 완료");
        } catch (Exception e) {
            return new PreMarketRecoveryResult(
                    false, 0, 0, "장 시작 사전 주문 복구 실패: " + trim(e.getMessage()));
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
        if (!props.isConfigured()) return;

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
                        "[자동매매][손절 전환 지연] {}({}), 익절 주문번호={}, 60초 동안 취소 확인이 되지 않아 일반 주문 동기화로 계속 확인합니다. 키움 주문 상태를 확인해 주세요.",
                        proposal.getStockName(),
                        proposal.getStockCode(),
                        proposal.getBrokerOrderNo());
                audit.log(
                        "STOP_CANCEL_CONFIRMATION_DELAYED",
                        proposal.getId(),
                        "손절 전환을 위한 익절 주문 취소가 60초 안에 확인되지 않았습니다.");
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
        try {
            return doSync();
        } finally {
            syncInProgress.set(false);
        }
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
            for (KiwoomTradeProposal proposal :
                    proposals.findByStatusIn(
                            List.of(KiwoomTradeProposal.Status.CANCEL_REQUESTED))) {
                boolean cancellationConfirmed =
                        records.stream()
                                .anyMatch(record -> isCancellationConfirmation(record, proposal));
                if (cancellationConfirmed) {
                    proposal.cancelled();
                    proposals.save(proposal);
                    audit.log(
                            "ORDER_CANCELLED_SYNC",
                            proposal.getId(),
                            "키움 주문상태·주문구분 응답으로 취소가 명시적으로 확인되었습니다.");
                    log.info(
                            "[자동매매][주문 취소 확인] {} {}({}), 원주문번호={}, 상태=취소 완료",
                            actionLabel(proposal.getAction()),
                            proposal.getStockName(),
                            proposal.getStockCode(),
                            proposal.getBrokerOrderNo());
                    updated++;
                }
            }
            if (updated > 0) exits.onOrderStateChanged();
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

    private String normalizeOrderNo(String value) {
        if (value == null || value.isBlank()) return null;
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.isBlank()) return value.trim();
        return digits.replaceFirst("^0+(?!$)", "");
    }

    private String orderSyncLabel(KiwoomTradeProposal proposal) {
        boolean takeProfit = isTakeProfit(proposal);
        if (!takeProfit) return statusLabel(proposal.getStatus());
        return switch (proposal.getStatus()) {
            case FILLED -> "익절 지정가 주문 체결 완료";
            case PARTIALLY_FILLED -> "익절 지정가 주문 일부 체결";
            case ORDERED -> "익절 지정가 주문 미체결";
            default -> "익절 지정가 주문 상태 변경";
        };
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

    private boolean isTakeProfit(KiwoomTradeProposal proposal) {
        return proposal.getReason() != null
                && proposal.getReason().startsWith("[EXIT:TAKE_PROFIT]");
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

    record PreMarketRecoveryResult(boolean success, int records, int expired, String message) {}
}
