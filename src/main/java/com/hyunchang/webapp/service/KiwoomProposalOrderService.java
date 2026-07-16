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
import org.springframework.stereotype.Service;

/** Broker submission is protected by both the approval state machine and pre-flight checks. */
@Service
public class KiwoomProposalOrderService {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final KiwoomTradeProposalRepository proposals;
    private final KiwoomTradeService trade;
    private final KiwoomProperties props;
    private final KiwoomWebsocketClient events;

    public KiwoomProposalOrderService(KiwoomTradeProposalRepository proposals, KiwoomTradeService trade, KiwoomProperties props, KiwoomWebsocketClient events) { this.proposals = proposals; this.trade = trade; this.props = props; this.events = events; }

    public Result approve(long id) {
        KiwoomTradeProposal p = find(id);
        if (p.getAction() == KiwoomTradeProposal.Action.HOLD) return fail("HOLD 제안은 주문 승인 대상이 아닙니다.");
        if (p.getStatus() != KiwoomTradeProposal.Status.PROPOSED) return fail("PROPOSED 상태의 제안만 승인할 수 있습니다.");
        if (hasGuards(p)) return fail("안전 경고가 있는 제안은 승인할 수 없습니다.");
        p.approve(); proposals.save(p); return ok(p, "제안을 승인했습니다. 주문은 아직 전송되지 않습니다.");
    }

    public Result reject(long id, String reason) {
        KiwoomTradeProposal p = find(id);
        if (p.getStatus() != KiwoomTradeProposal.Status.PROPOSED && p.getStatus() != KiwoomTradeProposal.Status.APPROVED) return fail("제안은 이미 처리되었습니다.");
        p.reject(reason == null || reason.isBlank() ? "사용자 거절" : reason); proposals.save(p); return ok(p, "제안을 거절했습니다.");
    }

    public Result draft(long id) {
        KiwoomTradeProposal p = find(id);
        if (p.getStatus() != KiwoomTradeProposal.Status.APPROVED) return fail("승인된 제안만 주문 초안으로 만들 수 있습니다.");
        p.draft(); proposals.save(p); return ok(p, "주문 초안을 만들었습니다. 최종 확인 전에는 전송되지 않습니다.");
    }

    public synchronized Result execute(long id, boolean confirmed) {
        KiwoomTradeProposal p = find(id);
        if (!confirmed) return fail("최종 확인이 필요합니다.");
        if (p.getStatus() != KiwoomTradeProposal.Status.ORDER_DRAFT) return fail("주문 초안 상태의 제안만 전송할 수 있습니다.");
        String preflight = preflightError(p);
        if (preflight != null) return fail(preflight);
        try {
            JsonNode response = trade.placeOrder(new KiwoomTradeService.OrderRequest(p.getAction().name(), p.getStockCode(), p.getQuantity(), p.getLimitPrice(), p.getOrderType().name(), "KRX")).block(Duration.ofSeconds(20));
            p.ordered(response == null ? "" : response.toString()); proposals.save(p); events.publishEvent("order", "승인된 주문을 키움에 전송했습니다: " + p.getStockCode()); return ok(p, "주문 전송 요청이 완료되었습니다.");
        } catch (Exception e) {
            p.orderFailed(trim(e.getMessage())); proposals.save(p); events.publishEvent("error", "주문 전송 실패: " + trim(e.getMessage())); return fail("주문 전송 실패: " + trim(e.getMessage()));
        }
    }

    private String preflightError(KiwoomTradeProposal p) {
        if (!props.isTradeEnabled()) return "주문 전송이 비활성화되어 있습니다. 현재는 안전한 dry-run 상태입니다.";
        if (hasGuards(p)) return "안전 경고가 있는 주문 초안은 전송할 수 없습니다.";
        if (!marketOpen()) return "장 운영 시간(평일 09:00~15:30 KST)에만 주문을 전송할 수 있습니다.";
        if (p.getOrderType() == KiwoomTradeProposal.OrderType.MARKET && !props.getStrategy().isAllowMarketOrders()) return "시장가 주문은 기본 차단되어 있습니다.";
        if (p.getOrderType() != KiwoomTradeProposal.OrderType.LIMIT || p.getLimitPrice() == null) return "실전 전송은 가격이 확정된 지정가 주문만 허용합니다.";
        if (p.getLimitPrice() * p.getQuantity() > props.getStrategy().getMaxOrderAmount()) return "주문 금액이 전략 최대 한도를 초과합니다.";
        long orderedToday = proposals.countByActionInAndStatusInAndCreatedAtGreaterThanEqual(List.of(KiwoomTradeProposal.Action.BUY, KiwoomTradeProposal.Action.SELL), List.of(KiwoomTradeProposal.Status.ORDERED), LocalDateTime.now(KST).toLocalDate().atStartOfDay());
        if (orderedToday >= props.getStrategy().getDailyMaxProposals()) return "오늘의 주문 전송 한도에 도달했습니다.";
        return null;
    }

    private boolean marketOpen() { LocalDateTime now = LocalDateTime.now(KST); LocalTime time = now.toLocalTime(); return now.getDayOfWeek() != DayOfWeek.SATURDAY && now.getDayOfWeek() != DayOfWeek.SUNDAY && !time.isBefore(LocalTime.of(9, 0)) && !time.isAfter(LocalTime.of(15, 30)); }
    private KiwoomTradeProposal find(long id) { return proposals.findById(id).orElseThrow(() -> new IllegalArgumentException("제안을 찾을 수 없습니다.")); }
    private boolean hasGuards(KiwoomTradeProposal p) { return p.getGuardFlags() != null && !p.getGuardFlags().isBlank(); }
    private String trim(String s) { return s == null ? "unknown" : s.substring(0, Math.min(500, s.length())); }
    private Result ok(KiwoomTradeProposal p, String message) { return new Result(true, message, p); }
    private Result fail(String message) { return new Result(false, message, null); }
    public record Result(boolean success, String message, KiwoomTradeProposal proposal) {}
}
