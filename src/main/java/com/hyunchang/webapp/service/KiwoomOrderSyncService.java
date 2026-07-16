package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.entity.KiwoomTradeProposal;
import com.hyunchang.webapp.repository.KiwoomTradeProposalRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Reconciles submitted domestic-stock orders with Kiwoom's unfilled/filled order inquiries. */
@Service
public class KiwoomOrderSyncService {
    private final KiwoomTradeService trade;
    private final KiwoomTradeProposalRepository proposals;
    private final KiwoomStrategyAuditService audit;
    private final KiwoomProperties props;

    public KiwoomOrderSyncService(
            KiwoomTradeService trade,
            KiwoomTradeProposalRepository proposals,
            KiwoomStrategyAuditService audit,
            KiwoomProperties props) {
        this.trade = trade;
        this.proposals = proposals;
        this.audit = audit;
        this.props = props;
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
            List<JsonNode> records = new ArrayList<>();
            collectRecords(trade.getUnfilledOrders().block(Duration.ofSeconds(15)), records);
            collectRecords(trade.getFilledOrders().block(Duration.ofSeconds(15)), records);
            int updated = 0;
            for (JsonNode record : records) if (apply(record)) updated++;
            for (KiwoomTradeProposal proposal :
                    proposals.findByStatusIn(
                            List.of(KiwoomTradeProposal.Status.CANCEL_REQUESTED))) {
                boolean stillUnfilled =
                        records.stream()
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
                            int ordered = number(record, "ord_qty", "order_qty", "qty");
                            int filled = number(record, "cntr_qty", "filled_qty", "exec_qty");
                            int remaining =
                                    number(record, "rmn_qty", "unfilled_qty", "ord_remain_qty");
                            if (filled == 0 && ordered > 0 && remaining >= 0)
                                filled = Math.max(0, ordered - remaining);
                            if (remaining == 0 && filled == 0) return false;
                            Long price =
                                    longValue(record, "cntr_prc", "avg_cntr_prc", "filled_price");
                            KiwoomTradeProposal.Status before = proposal.getStatus();
                            proposal.syncFill(filled, remaining, price);
                            proposals.save(proposal);
                            if (before != proposal.getStatus())
                                audit.log(
                                        "ORDER_STATUS_SYNC",
                                        proposal.getId(),
                                        "키움 주문 상태가 " + proposal.getStatus() + "로 변경되었습니다.");
                            return true;
                        })
                .orElse(false);
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

    private int number(JsonNode node, String... fields) {
        Long value = longValue(node, fields);
        return value == null ? 0 : value.intValue();
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
