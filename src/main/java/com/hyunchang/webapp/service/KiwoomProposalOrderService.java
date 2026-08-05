package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.entity.KiwoomTradeProposal;
import com.hyunchang.webapp.repository.KiwoomTradeProposalRepository;
import com.hyunchang.webapp.service.kiwoom.KiwoomAutoTradeState;
import com.hyunchang.webapp.service.kiwoom.KiwoomWebsocketClient;
import com.hyunchang.webapp.util.KiwoomMarketHours;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Iterator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Broker submission is protected by both the approval state machine and pre-flight checks. */
@Service
public class KiwoomProposalOrderService {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Logger log = LoggerFactory.getLogger(KiwoomProposalOrderService.class);
    private final KiwoomTradeProposalRepository proposals;
    private final KiwoomTradeService trade;
    private final KiwoomProperties props;
    private final KiwoomWebsocketClient events;
    private final KiwoomAutoTradeState state;
    private final KiwoomStrategyAuditService audit;
    private final KiwoomStrategySettingsService settings;

    public KiwoomProposalOrderService(
            KiwoomTradeProposalRepository proposals,
            KiwoomTradeService trade,
            KiwoomProperties props,
            KiwoomWebsocketClient events,
            KiwoomAutoTradeState state,
            KiwoomStrategyAuditService audit,
            KiwoomStrategySettingsService settings) {
        this.proposals = proposals;
        this.trade = trade;
        this.props = props;
        this.events = events;
        this.state = state;
        this.audit = audit;
        this.settings = settings;
    }

    public Result approve(long id) {
        KiwoomTradeProposal p = find(id);
        if (p.getAction() == KiwoomTradeProposal.Action.HOLD)
            return fail("HOLD 제안은 주문 승인 대상이 아닙니다.");
        if (p.getStatus() != KiwoomTradeProposal.Status.PROPOSED)
            return fail("PROPOSED 상태의 제안만 승인할 수 있습니다.");
        if (hasGuards(p)) return fail(guardBlockMessage(p, "제안을 승인할 수 없습니다."));
        p.approve();
        proposals.save(p);
        audit.log("PROPOSAL_APPROVED", p.getId(), "제안을 승인했습니다.");
        return ok(p, "제안을 승인했습니다. 주문은 아직 전송되지 않습니다.");
    }

    public Result reject(long id, String reason) {
        KiwoomTradeProposal p = find(id);
        if (p.getStatus() != KiwoomTradeProposal.Status.PROPOSED
                && p.getStatus() != KiwoomTradeProposal.Status.APPROVED)
            return fail("제안은 이미 처리되었습니다.");
        p.reject(reason == null || reason.isBlank() ? "사용자 거절" : reason);
        proposals.save(p);
        audit.log("PROPOSAL_REJECTED", p.getId(), "제안을 거절했습니다.");
        return ok(p, "제안을 거절했습니다.");
    }

    public Result draft(long id) {
        KiwoomTradeProposal p = find(id);
        if (p.getStatus() != KiwoomTradeProposal.Status.APPROVED)
            return fail("승인된 제안만 주문 초안으로 만들 수 있습니다.");
        p.draft();
        proposals.save(p);
        audit.log("ORDER_DRAFTED", p.getId(), "주문 초안을 만들었습니다.");
        return ok(p, "주문 초안을 만들었습니다. 최종 확인 전에는 전송되지 않습니다.");
    }

    public Result updateDraft(long id, int quantity, Long limitPrice) {
        KiwoomTradeProposal p = find(id);
        if (p.getStatus() != KiwoomTradeProposal.Status.ORDER_DRAFT)
            return fail("주문 초안 상태에서만 수량과 가격을 수정할 수 있습니다.");
        if (quantity <= 0 || limitPrice == null || limitPrice <= 0)
            return fail("수량과 지정가는 1 이상이어야 합니다.");
        if (limitPrice * quantity > props.getStrategy().getMaxOrderAmount())
            return fail("수정한 주문 금액이 전략 최대 한도를 초과합니다.");
        p.updateDraft(quantity, limitPrice);
        proposals.save(p);
        audit.log("ORDER_DRAFT_UPDATED", p.getId(), "주문 초안의 수량 또는 가격을 수정했습니다.");
        return ok(p, "주문 초안을 수정했습니다.");
    }

    public synchronized Result execute(long id, boolean confirmed) {
        KiwoomTradeProposal p = find(id);
        if (!confirmed) return fail("최종 확인이 필요합니다.");
        if (p.getStatus() != KiwoomTradeProposal.Status.ORDER_DRAFT)
            return fail("주문 초안 상태의 제안만 전송할 수 있습니다.");
        String preflight = preflightError(p);
        if (preflight != null) return fail(preflight);
        try {
            JsonNode response =
                    trade.placeOrder(
                                    new KiwoomTradeService.OrderRequest(
                                            p.getAction().name(),
                                            p.getStockCode(),
                                            p.getQuantity(),
                                            p.getLimitPrice(),
                                            p.getOrderType().name(),
                                            "KRX"))
                            .block(Duration.ofSeconds(20));
            String raw = response == null ? "" : response.toString();
            String orderNo = response == null ? null : response.path("ord_no").asText(null);
            if (orderNo == null || orderNo.isBlank()) {
                p.orderUnknown(raw);
                proposals.save(p);
                state.emergencyStop();
                audit.log(
                        "ORDER_UNKNOWN",
                        p.getId(),
                        "Broker response had no order number; automation was stopped to prevent duplicates.");
                events.publishEvent("error", "주문번호를 받지 못해 자동매매를 긴급 중지했습니다: " + p.getStockCode());
                return fail("주문번호가 없어 주문 상태를 확정할 수 없습니다. 자동매매를 중지했습니다.");
            }
            p.ordered(raw, orderNo);
            proposals.save(p);
            audit.log(
                    "ORDER_SUBMITTED",
                    p.getId(),
                    "키움 주문 전송 요청을 완료했습니다. 주문번호=" + (orderNo == null ? "미확인" : orderNo));
            events.publishEvent("order", "승인된 주문을 키움에 전송했습니다: " + p.getStockCode());
            log.info(
                    "[자동매매][주문 전송] {} {}({}), {}주, 지정가={}원, 주문번호={}, 주문 근거={}",
                    actionLabel(p.getAction()),
                    p.getStockName(),
                    p.getStockCode(),
                    p.getQuantity(),
                    p.getLimitPrice(),
                    orderNo,
                    orderConditionSummary(p));
            return ok(p, "주문 전송 요청이 완료되었습니다.");
        } catch (Exception e) {
            String failure = trim(e.getMessage());
            if (p.getAction() == KiwoomTradeProposal.Action.SELL)
                failure = trim(failure + " | " + sellAvailabilityDiagnostic(p.getStockCode()));
            p.orderFailed(failure);
            proposals.save(p);
            audit.log("ORDER_FAILED", p.getId(), failure);
            events.publishEvent("error", "주문 전송 실패: " + failure);
            log.warn(
                    "[자동매매][주문 전송 실패] {} {}({}), 사유={}",
                    actionLabel(p.getAction()),
                    p.getStockName(),
                    p.getStockCode(),
                    failure);
            return fail("주문 전송 실패: " + failure);
        }
    }

    private String actionLabel(KiwoomTradeProposal.Action action) {
        return action == KiwoomTradeProposal.Action.BUY ? "매수" : "매도";
    }

    private String orderConditionSummary(KiwoomTradeProposal proposal) {
        if (proposal.getAction() == KiwoomTradeProposal.Action.SELL)
            return "청산 사유=" + trim(proposal.getReason());
        var s = settings.current();
        return "AI 신뢰도 "
                + proposal.getConfidence()
                + "% (자동 기준 "
                + s.getAutoExecuteMinConfidence()
                + "% 이상), 후보 조건=상승률 +"
                + s.getSwingMinChangePercent()
                + "%~+"
                + s.getSwingMaxChangePercent()
                + "%, 거래량 "
                + s.getSwingMinVolumeRatio()
                + "배 이상, AI 판단="
                + trim(proposal.getReason());
    }

    private String sellAvailabilityDiagnostic(String stockCode) {
        try {
            JsonNode balance = trade.getBalance().block(Duration.ofSeconds(10));
            return trade.describeHoldingAvailability(balance, stockCode);
        } catch (Exception e) {
            return "매도가능수량 재조회 실패: " + trim(e.getMessage());
        }
    }

    /** 자동 전송도 승인→초안→전송의 동일 경로와 사전 점검을 그대로 거친다. 명시적 opt-in(DB 설정)일 때만 동작. */
    public synchronized Result autoExecute(long id) {
        KiwoomTradeProposal p = find(id);
        if (!settings.current().isAutoExecute()) return fail("자동 주문 전송이 비활성화되어 있습니다.");
        if (!state.isAutoTrading()) return fail("자동매매 엔진이 실행 중이 아닙니다.");
        if (p.getStatus() != KiwoomTradeProposal.Status.PROPOSED)
            return fail("자동 전송 대상 상태(PROPOSED)가 아닙니다.");
        if (p.getAction() == KiwoomTradeProposal.Action.HOLD) return fail("HOLD 제안은 전송 대상이 아닙니다.");
        if (p.getAction() == KiwoomTradeProposal.Action.BUY && state.isDailyLossTriggered())
            return fail("일일 손실 한도에 도달해 신규 매수 자동 전송이 차단되었습니다.");
        if (p.getConfidence() < settings.current().getAutoExecuteMinConfidence())
            return fail("신뢰도가 자동 전송 임계값보다 낮습니다.");
        if (p.getOrderType() != KiwoomTradeProposal.OrderType.LIMIT
                && !(p.getOrderType() == KiwoomTradeProposal.OrderType.MARKET
                        && p.getAction() == KiwoomTradeProposal.Action.SELL))
            return fail("자동 전송은 지정가 주문 또는 손절 시장가 매도만 지원합니다.");
        if (p.getOrderType() == KiwoomTradeProposal.OrderType.LIMIT && p.getLimitPrice() == null)
            return fail("지정가 주문에는 가격이 필요합니다.");
        if (hasGuards(p)) return fail(guardBlockMessage(p, "자동 전송할 수 없습니다."));
        Result approved = approve(id);
        if (!approved.success()) return approved;
        Result drafted = draft(id);
        if (!drafted.success()) return drafted;
        Result submitted = execute(id, true);
        if (submitted.success()) audit.log("ORDER_AUTO_SUBMITTED", id, "자동 전송 정책으로 주문을 전송했습니다.");
        return submitted;
    }

    public synchronized Result amend(long id, int quantity, Long limitPrice) {
        KiwoomTradeProposal p = find(id);
        if (state.isEmergencyStopped()) return fail("긴급 중지 상태에서는 주문을 정정할 수 없습니다.");
        if (!props.isTradeEnabled()) return fail("주문 전송이 비활성화되어 있습니다.");
        if (!isOpenOrder(p)) return fail("미체결·부분체결 상태의 주문만 정정할 수 있습니다.");
        if (p.getBrokerOrderNo() == null || p.getBrokerOrderNo().isBlank())
            return fail("주문번호가 없습니다. 먼저 주문 상태를 동기화하세요.");
        if (quantity <= 0
                || quantity > p.getRemainingQuantity()
                || limitPrice == null
                || limitPrice <= 0) return fail("정정 수량은 남은 수량 이내, 지정가는 1 이상이어야 합니다.");
        if (limitPrice * quantity > props.getStrategy().getMaxOrderAmount())
            return fail("정정 주문 금액이 전략 최대 한도를 초과합니다.");
        try {
            JsonNode response =
                    trade.amendOrder(
                                    new KiwoomTradeService.AmendOrderRequest(
                                            p.getBrokerOrderNo(),
                                            p.getStockCode(),
                                            quantity,
                                            limitPrice))
                            .block(Duration.ofSeconds(20));
            String raw = response == null ? "" : response.toString();
            String orderNo = response == null ? null : response.path("ord_no").asText(null);
            p.amended(quantity, limitPrice, raw, orderNo);
            proposals.save(p);
            audit.log("ORDER_AMEND_REQUESTED", p.getId(), "키움이 주문 정정 요청을 접수했습니다.");
            events.publishEvent("order", "주문 정정 요청: " + p.getStockCode());
            return ok(p, "주문 정정 요청이 접수되었습니다.");
        } catch (Exception e) {
            audit.log("ORDER_AMEND_FAILED", p.getId(), trim(e.getMessage()));
            return fail("주문 정정 실패: " + trim(e.getMessage()));
        }
    }

    /** 긴급 중지 중에도 취소는 허용한다 — 노출(포지션)을 줄이는 방향의 조작은 항상 가능해야 한다. */
    public synchronized Result cancel(long id, int quantity) {
        KiwoomTradeProposal p = find(id);
        if (!isOpenOrder(p)) return fail("미체결·부분체결 상태의 주문만 취소할 수 있습니다.");
        if (p.getBrokerOrderNo() == null || p.getBrokerOrderNo().isBlank())
            return fail("주문번호가 없습니다. 먼저 주문 상태를 동기화하세요.");
        if (quantity <= 0 || quantity > p.getRemainingQuantity())
            return fail("취소 수량은 남은 수량 이내여야 합니다.");
        try {
            JsonNode response =
                    trade.cancelOrder(
                                    new KiwoomTradeService.CancelOrderRequest(
                                            p.getBrokerOrderNo(), p.getStockCode(), quantity))
                            .block(Duration.ofSeconds(20));
            p.cancelRequested(response == null ? "" : response.toString());
            proposals.save(p);
            audit.log("ORDER_CANCEL_REQUESTED", p.getId(), "키움이 주문 취소 요청을 접수했습니다.");
            events.publishEvent("order", "주문 취소 요청: " + p.getStockCode());
            return ok(p, "주문 취소 요청이 접수되었습니다. 잠시 후 상태가 동기화됩니다.");
        } catch (Exception e) {
            audit.log("ORDER_CANCEL_FAILED", p.getId(), trim(e.getMessage()));
            return fail("주문 취소 실패: " + trim(e.getMessage()));
        }
    }

    private String preflightError(KiwoomTradeProposal p) {
        if (state.isEmergencyStopped()) return "긴급 중지 상태에서는 주문을 전송할 수 없습니다.";
        if (!props.isTradeEnabled()) return "주문 전송이 비활성화되어 있습니다.";
        if (hasGuards(p)) return guardBlockMessage(p, "주문 초안을 전송할 수 없습니다.");
        if (!KiwoomMarketHours.isOpen()) return "장 운영 시간(평일 09:00~15:30 KST)에만 주문을 전송할 수 있습니다.";
        if (p.getOrderType() == KiwoomTradeProposal.OrderType.MARKET
                && p.getAction() != KiwoomTradeProposal.Action.SELL
                && !props.getStrategy().isAllowMarketOrders()) return "시장가 주문은 기본 차단되어 있습니다.";
        if (p.getOrderType() != KiwoomTradeProposal.OrderType.LIMIT
                && !(p.getOrderType() == KiwoomTradeProposal.OrderType.MARKET
                        && p.getAction() == KiwoomTradeProposal.Action.SELL))
            return "실전 전송은 지정가 주문 또는 손절 시장가 매도만 허용합니다.";
        if (p.getOrderType() == KiwoomTradeProposal.OrderType.LIMIT && p.getLimitPrice() == null)
            return "지정가 주문에는 가격이 필요합니다.";
        if (p.getAction() == KiwoomTradeProposal.Action.BUY) {
            if (p.getOrderType() != KiwoomTradeProposal.OrderType.LIMIT)
                return "매수 자동 전송은 지정가 주문만 허용합니다.";
            if (state.isDailyLossTriggered()) return "일일 손실 한도에 도달했습니다. 오늘은 신규 매수를 전송할 수 없습니다.";
            long deposit = availableDeposit();
            if (deposit <= 0) return "주문 직전 예수금을 확인하지 못했습니다.";
            long amount = p.getLimitPrice() * p.getQuantity();
            if (amount > props.getStrategy().getMaxOrderAmount())
                return "Buy order amount exceeds the strategy limit.";
            if (amount > deposit) return "주문 금액이 현재 주문 가능 예수금을 초과합니다.";
            double percent = settings.current().getMaxBuyDepositPercent();
            long buyBudget = Math.round(deposit * percent / 100.0);
            if (amount > buyBudget) return "매수 금액이 예수금 대비 허용 비율(" + percent + "%)을 초과합니다.";
        }
        long filledToday =
                proposals.countByActionInAndStatusInAndCreatedAtGreaterThanEqual(
                        List.of(KiwoomTradeProposal.Action.BUY),
                        List.of(
                                KiwoomTradeProposal.Status.PARTIALLY_FILLED,
                                KiwoomTradeProposal.Status.FILLED),
                        LocalDateTime.now(KST).toLocalDate().atStartOfDay());
        if (p.getAction() == KiwoomTradeProposal.Action.BUY
                && filledToday >= settings.current().getDailyMaxProposals())
            return "오늘의 신규 매수 체결 건수 한도에 도달했습니다.";
        return null;
    }

    private long availableDeposit() {
        try {
            return findNumber(trade.getDeposit().block(Duration.ofSeconds(10)));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private long findNumber(JsonNode node) {
        if (node == null) return 0;
        for (String field : List.of("ord_alow_amt", "entr", "ord_psbl_cash"))
            if (node.hasNonNull(field) && node.path(field).asLong() > 0)
                return node.path(field).asLong();
        if (node.isArray())
            for (JsonNode child : node) {
                long value = findNumber(child);
                if (value > 0) return value;
            }
        if (node.isObject()) {
            Iterator<JsonNode> values = node.elements();
            while (values.hasNext()) {
                long value = findNumber(values.next());
                if (value > 0) return value;
            }
        }
        return 0;
    }

    private KiwoomTradeProposal find(long id) {
        return proposals
                .findById(id)
                .orElseThrow(() -> new IllegalArgumentException("제안을 찾을 수 없습니다."));
    }

    private boolean hasGuards(KiwoomTradeProposal p) {
        return p.getGuardFlags() != null && !p.getGuardFlags().isBlank();
    }

    private String guardBlockMessage(KiwoomTradeProposal proposal, String action) {
        return "안전 경고=" + guardSummary(proposal.getGuardFlags()) + " — " + action;
    }

    private String guardSummary(String guardFlags) {
        StringBuilder labels = new StringBuilder();
        for (String flag : guardFlags.split(",")) {
            if (flag == null || flag.isBlank()) continue;
            if (!labels.isEmpty()) labels.append(", ");
            labels.append(guardLabel(flag.trim()));
        }
        return labels.isEmpty() ? "확인 필요" : labels.toString();
    }

    private String guardLabel(String flag) {
        return switch (flag) {
            case "MAX_ORDER_AMOUNT" -> "주문 금액 상한 초과";
            case "DAILY_LIMIT" -> "오늘의 신규 매수 체결 건수 한도 도달";
            case "SYMBOL_COOLDOWN" -> "같은 종목 재주문 대기시간";
            case "MARKET_CLOSED" -> "장외 시간";
            case "INSUFFICIENT_DEPOSIT" -> "주문 가능 예수금 부족";
            case "MAX_BUY_BUDGET" -> "한 번 매수 예산 비율 초과";
            case "DAILY_LOSS_LIMIT" -> "일일 손실 한도 발동";
            default -> flag;
        };
    }

    private boolean isOpenOrder(KiwoomTradeProposal p) {
        return p.getStatus() == KiwoomTradeProposal.Status.ORDERED
                || p.getStatus() == KiwoomTradeProposal.Status.PARTIALLY_FILLED;
    }

    private String trim(String s) {
        return s == null ? "unknown" : s.substring(0, Math.min(500, s.length()));
    }

    private Result ok(KiwoomTradeProposal p, String message) {
        return new Result(true, message, p);
    }

    private Result fail(String message) {
        return new Result(false, message, null);
    }

    public record Result(boolean success, String message, KiwoomTradeProposal proposal) {}
}
