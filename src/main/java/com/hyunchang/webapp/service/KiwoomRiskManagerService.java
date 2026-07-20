package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.entity.KiwoomStrategyRun;
import com.hyunchang.webapp.entity.KiwoomTradeProposal;
import com.hyunchang.webapp.repository.KiwoomStrategyRunRepository;
import com.hyunchang.webapp.repository.KiwoomTradeProposalRepository;
import com.hyunchang.webapp.util.KiwoomMarketHours;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 손절·익절·보유기간 청산 집행 루프. 장중 주기적으로 보유 종목을 평가해 규칙 기반 SELL 제안을 생성한다. 전송은 KiwoomProposalOrderService의 기존
 * 승인·자동전송 게이트를 그대로 공유한다 — 이 서비스가 직접 주문을 내지 않는다.
 */
@Service
public class KiwoomRiskManagerService {
    /** 동일 종목 중복 청산을 막기 위한 "열린 SELL" 상태 — 이 상태의 SELL이 있으면 재제안하지 않는다. */
    private static final List<KiwoomTradeProposal.Status> OPEN_SELL_STATUSES =
            List.of(
                    KiwoomTradeProposal.Status.PROPOSED,
                    KiwoomTradeProposal.Status.APPROVED,
                    KiwoomTradeProposal.Status.ORDER_DRAFT,
                    KiwoomTradeProposal.Status.ORDERED,
                    KiwoomTradeProposal.Status.PARTIALLY_FILLED,
                    KiwoomTradeProposal.Status.CANCEL_REQUESTED);

    private final KiwoomProperties props;
    private final KiwoomTradeService trade;
    private final KiwoomAutoTradeState state;
    private final KiwoomStrategySettingsService settings;
    private final KiwoomTradeProposalRepository proposals;
    private final KiwoomStrategyRunRepository runs;
    private final KiwoomProposalOrderService orders;
    private final KiwoomStrategyAuditService audit;
    private final KiwoomWebsocketClient events;
    private volatile LocalDateTime lastScanAt;

    public KiwoomRiskManagerService(
            KiwoomProperties props,
            KiwoomTradeService trade,
            KiwoomAutoTradeState state,
            KiwoomStrategySettingsService settings,
            KiwoomTradeProposalRepository proposals,
            KiwoomStrategyRunRepository runs,
            KiwoomProposalOrderService orders,
            KiwoomStrategyAuditService audit,
            KiwoomWebsocketClient events) {
        this.props = props;
        this.trade = trade;
        this.state = state;
        this.settings = settings;
        this.proposals = proposals;
        this.runs = runs;
        this.orders = orders;
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

    /** AI 판단(runDecision)과 단일 실행 가드를 공유한다 — 동시에 돌면 같은 종목에 SELL이 중복 생성될 수 있다. */
    public RiskScanResult runRiskScan(String by) {
        if (state.isEmergencyStopped())
            throw new IllegalStateException("긴급 중지 상태에서는 리스크 스캔을 실행할 수 없습니다.");
        if (!state.tryStartDecision()) throw new IllegalStateException("이미 전략 판단이 실행 중입니다.");
        try {
            JsonNode depositNode = trade.getDeposit().block(Duration.ofSeconds(10));
            JsonNode balance = trade.getBalance().block(Duration.ofSeconds(10));
            long deposit = number(depositNode, "ord_alow_amt", "entr");
            var current = settings.current();

            if (state.recordDailyLossCheck(
                    deposit + trade.totalEvaluationAmount(balance),
                    current.getDailyLossLimitAmount())) {
                audit.log("DAILY_LOSS_TRIGGERED", null, "일일 손실 한도에 도달해 신규 매수를 차단합니다.");
                events.publishEvent("strategy", "일일 손실 한도 발동 — 오늘 남은 시간 동안 신규 매수를 차단합니다.");
            }
            boolean dailyLossTriggered = state.isDailyLossTriggered();
            lastScanAt = LocalDateTime.now();

            if (!current.isRiskLoopEnabled()) {
                return new RiskScanResult(
                        null, 0, 0, dailyLossTriggered, "리스크 루프가 꺼져 있어 일일 손실 체크만 수행했습니다.");
            }

            List<KiwoomTradeService.Holding> holdings = trade.parseHoldings(balance);
            KiwoomStrategyRun run = null;
            int created = 0;
            for (KiwoomTradeService.Holding h : holdings) {
                Optional<Trigger> trigger = evaluate(h, current);
                if (trigger.isEmpty()) continue;
                if (run == null) run = newRiskRun(by);
                KiwoomTradeProposal p = buildSellProposal(run, h, trigger.get());
                if (p == null) continue;
                proposals.save(p);
                created++;
                audit.log("RISK_SELL_PROPOSED", p.getId(), p.getReason());
                events.publishEvent(
                        "strategy", "리스크 청산 SELL 제안: " + h.name() + "(" + h.code() + ")");
                autoSubmit(p);
            }
            String message =
                    created == 0
                            ? "청산 조건에 해당하는 보유 종목이 없습니다."
                            : "리스크 청산 SELL 제안 " + created + "건을 생성했습니다.";
            return new RiskScanResult(
                    run == null ? null : run.getId(),
                    holdings.size(),
                    created,
                    dailyLossTriggered,
                    message);
        } finally {
            state.finishDecision();
        }
    }

    public LocalDateTime getLastScanAt() {
        return lastScanAt;
    }

    private KiwoomStrategyRun newRiskRun(String by) {
        KiwoomStrategyRun run = new KiwoomStrategyRun();
        run.setTriggeredBy(KiwoomStrategyRun.TriggeredBy.RISK);
        run.setStatus(KiwoomStrategyRun.Status.SUCCESS);
        run.setProviderName("RISK_ENGINE");
        run.setModel(by);
        run.setMarketView("손절·익절·보유기간 규칙에 따른 리스크 청산 스캔");
        return runs.save(run);
    }

    /** 우선순위: 손절 > 익절 > 시간 청산. 비율이 0 이하로 설정된 조건은 비활성으로 본다. */
    private Optional<Trigger> evaluate(
            KiwoomTradeService.Holding h, com.hyunchang.webapp.entity.KiwoomStrategySettings s) {
        if (h.avgPrice() <= 0 || h.curPrice() <= 0 || h.sellable() <= 0) return Optional.empty();
        if (hasOpenSell(h.code())) return Optional.empty();
        double plPct = (h.curPrice() - h.avgPrice()) * 100.0 / h.avgPrice();
        if (s.getSwingStopLossPercent() > 0
                && h.curPrice() <= h.avgPrice() * (100 - s.getSwingStopLossPercent()) / 100.0) {
            return Optional.of(
                    new Trigger(
                            TriggerType.STOP_LOSS,
                            describe(
                                    h,
                                    plPct,
                                    "기준 -" + s.getSwingStopLossPercent() + "%",
                                    "손절 청산")));
        }
        if (s.getSwingTakeProfitPercent() > 0
                && h.curPrice() >= h.avgPrice() * (100 + s.getSwingTakeProfitPercent()) / 100.0) {
            return Optional.of(
                    new Trigger(
                            TriggerType.TAKE_PROFIT,
                            describe(
                                    h,
                                    plPct,
                                    "기준 +" + s.getSwingTakeProfitPercent() + "%",
                                    "익절 청산")));
        }
        long heldDays = engineHoldingDays(h.code());
        if (heldDays > s.getSwingMaxHoldingDays()) {
            return Optional.of(
                    new Trigger(
                            TriggerType.TIME_EXIT,
                            describe(
                                    h,
                                    plPct,
                                    "보유 " + heldDays + "일 · 기준 " + s.getSwingMaxHoldingDays() + "일",
                                    "보유기간 초과 청산")));
        }
        return Optional.empty();
    }

    private String describe(KiwoomTradeService.Holding h, double plPct, String rule, String label) {
        return "평단 "
                + String.format("%,d", h.avgPrice())
                + "원 · 현재가 "
                + String.format("%,d", h.curPrice())
                + "원 ("
                + (plPct >= 0 ? "+" : "")
                + String.format("%.2f", plPct)
                + "%, "
                + rule
                + ") "
                + label;
    }

    /** 엔진이 체결까지 완료한 가장 최근 BUY 기준 보유 일수. 엔진 매수 이력이 없는(수동 매수) 종목은 0 — 시간 청산 대상에서 제외된다. */
    private long engineHoldingDays(String code) {
        return proposals
                .findFirstByStockCodeAndActionAndStatusOrderByCreatedAtDesc(
                        code, KiwoomTradeProposal.Action.BUY, KiwoomTradeProposal.Status.FILLED)
                .map(
                        p ->
                                ChronoUnit.DAYS.between(
                                        p.getCreatedAt().toLocalDate(),
                                        LocalDate.now(KiwoomMarketHours.KST)))
                .orElse(0L);
    }

    private boolean hasOpenSell(String code) {
        return proposals.existsByStockCodeAndActionAndStatusIn(
                code, KiwoomTradeProposal.Action.SELL, OPEN_SELL_STATUSES);
    }

    /**
     * 규칙 기반 청산 제안. AI 제안과 달리 쿨다운·일일 제안 한도 가드를 부여하지 않는다 — 보호성 청산이 스로틀에 막히면 기능이 무력화된다. 단 전송 시점의
     * preflight(일일 전송 한도·예수금 등)는 그대로 적용되는 알려진 한계가 있다.
     */
    private KiwoomTradeProposal buildSellProposal(
            KiwoomStrategyRun run, KiwoomTradeService.Holding h, Trigger t) {
        long maxQtyByAmount = props.getStrategy().getMaxOrderAmount() / h.curPrice();
        int qty = (int) Math.min(h.sellable(), maxQtyByAmount);
        if (qty <= 0) {
            audit.log("RISK_SELL_SKIPPED", null, h.code() + " 1주 금액이 1회 주문 한도를 초과해 청산 제안을 건너뜁니다.");
            return null;
        }
        KiwoomTradeProposal p = new KiwoomTradeProposal();
        p.setRun(run);
        p.setAction(KiwoomTradeProposal.Action.SELL);
        p.setStockCode(h.code());
        p.setStockName(h.name());
        p.setQuantity(qty);
        p.setOrderType(KiwoomTradeProposal.OrderType.LIMIT);
        p.setLimitPrice(h.curPrice());
        // 규칙 기반 청산은 AI 신뢰도 개념이 없다 — 자동 전송 신뢰도 게이트를 통과하도록 100으로 저장한다.
        p.setConfidence(100);
        String partial = qty < h.sellable() ? " (주문 한도로 " + qty + "주 부분 청산)" : "";
        p.setReason("[RISK:" + t.type() + "] " + t.reason() + partial);
        p.setGuardFlags(KiwoomMarketHours.isOpen() ? "" : "MARKET_CLOSED");
        return p;
    }

    private void autoSubmit(KiwoomTradeProposal p) {
        if (!settings.current().isAutoExecute()) return;
        KiwoomProposalOrderService.Result result = orders.autoExecute(p.getId());
        if (result.success()) {
            events.publishEvent("order", "리스크 청산 주문을 자동 전송했습니다: " + p.getStockCode());
        } else {
            events.publishEvent(
                    "strategy",
                    "리스크 청산 자동 전송 건너뜀: " + p.getStockCode() + " (" + result.message() + ")");
        }
    }

    private long number(JsonNode n, String... names) {
        if (n != null) for (String x : names) if (n.has(x)) return n.path(x).asLong();
        return 0;
    }

    public enum TriggerType {
        STOP_LOSS,
        TAKE_PROFIT,
        TIME_EXIT
    }

    private record Trigger(TriggerType type, String reason) {}

    public record RiskScanResult(
            Long runId,
            int holdingCount,
            int proposalCount,
            boolean dailyLossTriggered,
            String message) {}
}
