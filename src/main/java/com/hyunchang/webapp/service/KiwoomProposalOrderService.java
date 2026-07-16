package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.entity.KiwoomTradeProposal;
import com.hyunchang.webapp.repository.KiwoomTradeProposalRepository;
import java.time.Duration;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Iterator;
import org.springframework.stereotype.Service;

/** Broker submission is protected by both the approval state machine and pre-flight checks. */
@Service
public class KiwoomProposalOrderService {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final KiwoomTradeProposalRepository proposals;
    private final KiwoomTradeService trade;
    private final KiwoomProperties props;
    private final KiwoomWebsocketClient events;
    private final KiwoomAutoTradeState state;
    private final KiwoomStrategyAuditService audit;

    public KiwoomProposalOrderService(KiwoomTradeProposalRepository proposals, KiwoomTradeService trade, KiwoomProperties props, KiwoomWebsocketClient events, KiwoomAutoTradeState state, KiwoomStrategyAuditService audit) { this.proposals = proposals; this.trade = trade; this.props = props; this.events = events; this.state = state; this.audit = audit; }

    public Result approve(long id) {
        KiwoomTradeProposal p = find(id);
        if (p.getAction() == KiwoomTradeProposal.Action.HOLD) return fail("HOLD 제안은 주문 승인 대상이 아닙니다.");
        if (p.getStatus() != KiwoomTradeProposal.Status.PROPOSED) return fail("PROPOSED 상태의 제안만 승인할 수 있습니다.");
        if (hasGuards(p)) return fail("안전 경고가 있는 제안은 승인할 수 없습니다.");
        p.approve(); proposals.save(p); audit.log("PROPOSAL_APPROVED", p.getId(), "제안을 승인했습니다."); return ok(p, "제안을 승인했습니다. 주문은 아직 전송되지 않습니다.");
    }

    public Result reject(long id, String reason) {
        KiwoomTradeProposal p = find(id);
        if (p.getStatus() != KiwoomTradeProposal.Status.PROPOSED && p.getStatus() != KiwoomTradeProposal.Status.APPROVED) return fail("제안은 이미 처리되었습니다.");
        p.reject(reason == null || reason.isBlank() ? "사용자 거절" : reason); proposals.save(p); audit.log("PROPOSAL_REJECTED", p.getId(), "제안을 거절했습니다."); return ok(p, "제안을 거절했습니다.");
    }

    public Result draft(long id) {
        KiwoomTradeProposal p = find(id);
        if (p.getStatus() != KiwoomTradeProposal.Status.APPROVED) return fail("승인된 제안만 주문 초안으로 만들 수 있습니다.");
        p.draft(); proposals.save(p); audit.log("ORDER_DRAFTED", p.getId(), "주문 초안을 만들었습니다."); return ok(p, "주문 초안을 만들었습니다. 최종 확인 전에는 전송되지 않습니다.");
    }

    public Result updateDraft(long id, int quantity, Long limitPrice) {
        KiwoomTradeProposal p = find(id);
        if (p.getStatus() != KiwoomTradeProposal.Status.ORDER_DRAFT) return fail("주문 초안 상태에서만 수량과 가격을 수정할 수 있습니다.");
        if (quantity <= 0 || limitPrice == null || limitPrice <= 0) return fail("수량과 지정가는 1 이상이어야 합니다.");
        if (limitPrice * quantity > props.getStrategy().getMaxOrderAmount()) return fail("수정한 주문 금액이 전략 최대 한도를 초과합니다.");
        p.updateDraft(quantity, limitPrice); proposals.save(p); audit.log("ORDER_DRAFT_UPDATED", p.getId(), "주문 초안의 수량 또는 가격을 수정했습니다."); return ok(p, "주문 초안을 수정했습니다.");
    }

    public synchronized Result execute(long id, boolean confirmed) {
        KiwoomTradeProposal p = find(id);
        if (!confirmed) return fail("최종 확인이 필요합니다.");
        if (p.getStatus() != KiwoomTradeProposal.Status.ORDER_DRAFT) return fail("주문 초안 상태의 제안만 전송할 수 있습니다.");
        String preflight = preflightError(p);
        if (preflight != null) return fail(preflight);
        try {
            JsonNode response = trade.placeOrder(new KiwoomTradeService.OrderRequest(p.getAction().name(), p.getStockCode(), p.getQuantity(), p.getLimitPrice(), p.getOrderType().name(), "KRX")).block(Duration.ofSeconds(20));
            String raw = response == null ? "" : response.toString(); String orderNo = response == null ? null : response.path("ord_no").asText(null); p.ordered(raw, orderNo); proposals.save(p); audit.log("ORDER_SUBMITTED", p.getId(), "키움 주문 전송 요청을 완료했습니다. 주문번호=" + (orderNo == null ? "미확인" : orderNo)); events.publishEvent("order", "승인된 주문을 키움에 전송했습니다: " + p.getStockCode()); return ok(p, "주문 전송 요청이 완료되었습니다.");
        } catch (Exception e) {
            p.orderFailed(trim(e.getMessage())); proposals.save(p); audit.log("ORDER_FAILED", p.getId(), trim(e.getMessage())); events.publishEvent("error", "주문 전송 실패: " + trim(e.getMessage())); return fail("주문 전송 실패: " + trim(e.getMessage()));
        }
    }

    /** Uses the normal approval and pre-flight path; unattended submission is opt-in. */
    public synchronized Result autoExecute(long id) {
        KiwoomTradeProposal p = find(id);
        if (!props.getStrategy().isAutoExecute()) return fail("Automatic submission is disabled.");
        if (!state.isAutoTrading()) return fail("Auto-trading engine is not running.");
        if (p.getStatus() != KiwoomTradeProposal.Status.PROPOSED) return fail("Proposal is not eligible for automatic submission.");
        if (p.getAction() == KiwoomTradeProposal.Action.HOLD) return fail("HOLD proposals cannot be submitted.");
        if (p.getConfidence() < props.getStrategy().getAutoExecuteMinConfidence()) return fail("Confidence is below automatic submission threshold.");
        if (p.getOrderType() != KiwoomTradeProposal.OrderType.LIMIT || p.getLimitPrice() == null) return fail("Only limit orders can be submitted automatically.");
        if (hasGuards(p)) return fail("Proposal has safety guards.");
        Result approved = approve(id);
        if (!approved.success()) return approved;
        Result drafted = draft(id);
        if (!drafted.success()) return drafted;
        Result submitted = execute(id, true);
        if (submitted.success()) audit.log("ORDER_AUTO_SUBMITTED", id, "Automatic policy submitted this order.");
        return submitted;
    }

    public synchronized Result amend(long id, int quantity, Long limitPrice) {
        KiwoomTradeProposal p = find(id);
        if (state.isEmergencyStopped()) return fail("Emergency stop is active; order amendments are blocked.");
        if (!props.isTradeEnabled()) return fail("Order transmission is disabled.");
        if (!isOpenOrder(p)) return fail("Only open or partially-filled orders can be amended.");
        if (p.getBrokerOrderNo() == null || p.getBrokerOrderNo().isBlank()) return fail("Broker order number is unavailable; synchronize the order first.");
        if (quantity <= 0 || quantity > p.getRemainingQuantity() || limitPrice == null || limitPrice <= 0) return fail("Amendment quantity must be within the remaining quantity and price must be positive.");
        if (limitPrice * quantity > props.getStrategy().getMaxOrderAmount()) return fail("Amended order exceeds the configured maximum order amount.");
        try {
            JsonNode response = trade.amendOrder(new KiwoomTradeService.AmendOrderRequest(p.getBrokerOrderNo(), p.getStockCode(), quantity, limitPrice)).block(Duration.ofSeconds(20));
            String raw = response == null ? "" : response.toString(); String orderNo = response == null ? null : response.path("ord_no").asText(null);
            p.amended(quantity, limitPrice, raw, orderNo); proposals.save(p); audit.log("ORDER_AMEND_REQUESTED", p.getId(), "Order amendment was accepted by the broker."); events.publishEvent("order", "Order amendment requested: " + p.getStockCode()); return ok(p, "Order amendment request was accepted.");
        } catch (Exception e) { audit.log("ORDER_AMEND_FAILED", p.getId(), trim(e.getMessage())); return fail("Order amendment failed: " + trim(e.getMessage())); }
    }

    /** Cancellation remains available during emergency stop so exposure can always be reduced. */
    public synchronized Result cancel(long id, int quantity) {
        KiwoomTradeProposal p = find(id);
        if (!isOpenOrder(p)) return fail("Only open or partially-filled orders can be cancelled.");
        if (p.getBrokerOrderNo() == null || p.getBrokerOrderNo().isBlank()) return fail("Broker order number is unavailable; synchronize the order first.");
        if (quantity <= 0 || quantity > p.getRemainingQuantity()) return fail("Cancellation quantity must be within the remaining quantity.");
        try {
            JsonNode response = trade.cancelOrder(new KiwoomTradeService.CancelOrderRequest(p.getBrokerOrderNo(), p.getStockCode(), quantity)).block(Duration.ofSeconds(20));
            p.cancelRequested(response == null ? "" : response.toString()); proposals.save(p); audit.log("ORDER_CANCEL_REQUESTED", p.getId(), "Order cancellation was accepted by the broker."); events.publishEvent("order", "Order cancellation requested: " + p.getStockCode()); return ok(p, "Order cancellation request was accepted; it will be reconciled shortly.");
        } catch (Exception e) { audit.log("ORDER_CANCEL_FAILED", p.getId(), trim(e.getMessage())); return fail("Order cancellation failed: " + trim(e.getMessage())); }
    }

    private String preflightError(KiwoomTradeProposal p) {
        if (state.isEmergencyStopped()) return "긴급 중지 상태에서는 주문을 전송할 수 없습니다.";
        if (!props.isTradeEnabled()) return "주문 전송이 비활성화되어 있습니다. 현재는 안전한 dry-run 상태입니다.";
        if (hasGuards(p)) return "안전 경고가 있는 주문 초안은 전송할 수 없습니다.";
        if (!marketOpen()) return "장 운영 시간(평일 09:00~15:30 KST)에만 주문을 전송할 수 있습니다.";
        if (p.getOrderType() == KiwoomTradeProposal.OrderType.MARKET && !props.getStrategy().isAllowMarketOrders()) return "시장가 주문은 기본 차단되어 있습니다.";
        if (p.getOrderType() != KiwoomTradeProposal.OrderType.LIMIT || p.getLimitPrice() == null) return "실전 전송은 가격이 확정된 지정가 주문만 허용합니다.";
        if (p.getLimitPrice() * p.getQuantity() > props.getStrategy().getMaxOrderAmount()) return "주문 금액이 전략 최대 한도를 초과합니다.";
        if (p.getAction() == KiwoomTradeProposal.Action.BUY) {
            long deposit = availableDeposit();
            if (deposit <= 0) return "주문 직전 예수금을 확인하지 못했습니다.";
            if (p.getLimitPrice() * p.getQuantity() > deposit) return "주문 금액이 현재 주문 가능 예수금을 초과합니다.";
        }
        if (p.getAction() == KiwoomTradeProposal.Action.BUY) {
            long deposit = availableDeposit();
            long buyBudget = Math.round(deposit * props.getStrategy().getMaxBuyDepositPercent() / 100.0);
            if (p.getLimitPrice() * p.getQuantity() > buyBudget) return "Buy order exceeds the configured percentage of available deposit.";
        }
        long orderedToday = proposals.countByActionInAndStatusInAndCreatedAtGreaterThanEqual(List.of(KiwoomTradeProposal.Action.BUY, KiwoomTradeProposal.Action.SELL), List.of(KiwoomTradeProposal.Status.ORDERED), LocalDateTime.now(KST).toLocalDate().atStartOfDay());
        if (orderedToday >= props.getStrategy().getDailyMaxProposals()) return "오늘의 주문 전송 한도에 도달했습니다.";
        return null;
    }

    private long availableDeposit() {
        try { return findNumber(trade.getDeposit().block(Duration.ofSeconds(10))); } catch (Exception ignored) { return 0; }
    }

    private long findNumber(JsonNode node) {
        if (node == null) return 0;
        for (String field : List.of("ord_alow_amt", "entr", "ord_psbl_cash")) if (node.hasNonNull(field) && node.path(field).asLong() > 0) return node.path(field).asLong();
        if (node.isArray()) for (JsonNode child : node) { long value = findNumber(child); if (value > 0) return value; }
        if (node.isObject()) { Iterator<JsonNode> values = node.elements(); while (values.hasNext()) { long value = findNumber(values.next()); if (value > 0) return value; } }
        return 0;
    }

    private boolean marketOpen() { LocalDateTime now = LocalDateTime.now(KST); LocalTime time = now.toLocalTime(); return now.getDayOfWeek() != DayOfWeek.SATURDAY && now.getDayOfWeek() != DayOfWeek.SUNDAY && !time.isBefore(LocalTime.of(9, 0)) && !time.isAfter(LocalTime.of(15, 30)); }
    private KiwoomTradeProposal find(long id) { return proposals.findById(id).orElseThrow(() -> new IllegalArgumentException("제안을 찾을 수 없습니다.")); }
    private boolean hasGuards(KiwoomTradeProposal p) { return p.getGuardFlags() != null && !p.getGuardFlags().isBlank(); }
    private boolean isOpenOrder(KiwoomTradeProposal p) { return p.getStatus() == KiwoomTradeProposal.Status.ORDERED || p.getStatus() == KiwoomTradeProposal.Status.PARTIALLY_FILLED; }
    private String trim(String s) { return s == null ? "unknown" : s.substring(0, Math.min(500, s.length())); }
    private Result ok(KiwoomTradeProposal p, String message) { return new Result(true, message, p); }
    private Result fail(String message) { return new Result(false, message, null); }
    public record Result(boolean success, String message, KiwoomTradeProposal proposal) {}
}
