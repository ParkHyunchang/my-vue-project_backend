package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.entity.KiwoomTradeProposal;
import com.hyunchang.webapp.repository.KiwoomTradeProposalRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
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
    private final KiwoomTradeService trade;
    private final KiwoomTradeProposalRepository proposals;
    private final KiwoomStrategyAuditService audit;
    private final KiwoomProperties props;
    private final KiwoomPositionExitService exits;

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
                boolean stillUnfilled =
                        unfilledRecords.stream()
                                .anyMatch(
                                        record ->
                                                proposal.getBrokerOrderNo() != null
                                                        && proposal.getBrokerOrderNo()
                                                                .equals(
                                                                        text(
                                                                                record,
                                                                                "ord_no",
                                                                                "order_no")));
                if (!stillUnfilled) {
                    proposal.cancelled();
                    proposals.save(proposal);
                    audit.log("ORDER_CANCELLED_SYNC", proposal.getId(), "키움 주문 조회로 취소가 확정되었습니다.");
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
                            Long orderedValue = longValue(record, "ord_qty", "order_qty", "qty");
                            Long filledValue =
                                    longValue(record, "cntr_qty", "filled_qty", "exec_qty");
                            Long remainingValue =
                                    longValue(record, "rmn_qty", "unfilled_qty", "ord_remain_qty");

                            // A missing field is not the same as zero.  In particular, treating an
                            // omitted remaining quantity as zero incorrectly changed live limit orders
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
                                    longValue(record, "cntr_prc", "avg_cntr_prc", "filled_price");
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
                                                : String.format("%,d", proposal.getAverageFillPrice()),
                                        orderNo,
                                        proposal.getReason());
                            return changed;
                        })
                .orElse(false);
    }

    private String orderSyncLabel(KiwoomTradeProposal proposal) {
        boolean takeProfit =
                proposal.getReason() != null
                        && proposal.getReason().startsWith("[EXIT:TAKE_PROFIT]");
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
}
