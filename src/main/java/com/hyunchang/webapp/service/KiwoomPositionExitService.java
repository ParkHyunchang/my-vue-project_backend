package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.entity.KiwoomStrategyRun;
import com.hyunchang.webapp.entity.KiwoomTradeProposal;
import com.hyunchang.webapp.repository.KiwoomStrategyRunRepository;
import com.hyunchang.webapp.repository.KiwoomTradeProposalRepository;
import com.hyunchang.webapp.util.KiwoomMarketHours;
import com.hyunchang.webapp.util.KiwoomPriceRules;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Maintains one broker-side take-profit limit order per automated position and watches stop-loss
 * levels from 0B real-time ticks. Tick comparisons are intentionally memory-only: balance is read
 * only at startup, after an order state change, or while WebSocket fallback protection is active.
 */
@Service
public class KiwoomPositionExitService {
    private static final Logger log = LoggerFactory.getLogger(KiwoomPositionExitService.class);
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
    private final KiwoomWebsocketClient websocket;
    private final KiwoomAccountHoldingSyncService accountHoldings;
    private final Map<String, Position> positions = new ConcurrentHashMap<>();
    private final Set<String> stopPending = ConcurrentHashMap.newKeySet();
    private final Set<String> stopSubmitted = ConcurrentHashMap.newKeySet();

    public KiwoomPositionExitService(
            KiwoomProperties props,
            KiwoomTradeService trade,
            KiwoomAutoTradeState state,
            KiwoomStrategySettingsService settings,
            KiwoomTradeProposalRepository proposals,
            KiwoomStrategyRunRepository runs,
            KiwoomProposalOrderService orders,
            KiwoomStrategyAuditService audit,
            KiwoomWebsocketClient websocket,
            KiwoomAccountHoldingSyncService accountHoldings) {
        this.props = props;
        this.trade = trade;
        this.state = state;
        this.settings = settings;
        this.proposals = proposals;
        this.runs = runs;
        this.orders = orders;
        this.audit = audit;
        this.websocket = websocket;
        this.accountHoldings = accountHoldings;
    }

    @PostConstruct
    void listenForPriceTicks() {
        websocket.addPriceListener(tick -> onPriceTick(tick.stockCode(), tick.price()));
    }

    /**
     * Called when automation starts, after restart recovery, and after an actual order-state
     * change.
     */
    public synchronized void refreshPositions(String source) {
        if (!canManageExits()) return;
        try {
            JsonNode balance = trade.getBalance().block(Duration.ofSeconds(10));
            accountHoldings.syncBalance(balance, source);
            List<KiwoomTradeService.Holding> holdings = trade.parseHoldings(balance);
            Map<String, Position> refreshed = new HashMap<>();
            for (KiwoomTradeService.Holding holding : holdings) {
                if (!isAutomatedPosition(holding)
                        || holding.avgPrice() <= 0
                        || holding.sellable() <= 0) continue;
                Position position = toPosition(holding);
                refreshed.put(position.stockCode(), position);
            }
            positions.clear();
            positions.putAll(refreshed);
            stopPending.retainAll(refreshed.keySet());
            stopSubmitted.retainAll(refreshed.keySet());
            websocket.connectAndSubscribe(new ArrayList<>(refreshed.keySet()));

            for (Position position : refreshed.values()) {
                if (!stopPending.contains(position.stockCode())
                        && !stopSubmitted.contains(position.stockCode()))
                    ensureTakeProfitOrder(position);
            }
            submitConfirmedStopOrders(refreshed.values());
        } catch (Exception e) {
            // No recurring log for refresh failures. The active WebSocket feed remains the primary
            // path.
        }
    }

    /**
     * A disconnected real-time feed falls back to one balance check per minute for stop-loss
     * safety.
     */
    @Scheduled(fixedDelay = 60000)
    public void fallbackWhenRealtimeDisconnected() {
        if (!canManageExits() || websocket.isConnected() || !KiwoomMarketHours.isOpen()) return;
        refreshPositions("WEBSOCKET_FALLBACK");
        for (Position position : positions.values())
            onPriceTick(position.stockCode(), position.currentPrice());
    }

    /** 전일 매수분처럼 매도가능수량이 장 시작과 함께 바뀌는 종목의 익절 주문을 다시 건다. */
    @Scheduled(cron = "0 1 9 * * MON-FRI", zone = "Asia/Seoul")
    public void refreshAtMarketOpen() {
        refreshPositions("MARKET_OPEN_RECHECK");
    }

    /** Invoked by the order synchronizer only when a broker order actually changed state. */
    public void onOrderStateChanged() {
        refreshPositions("ORDER_STATE_CHANGED");
    }

    private synchronized void onPriceTick(String stockCode, long currentPrice) {
        Position position = positions.get(stockCode);
        if (position == null || currentPrice <= 0 || !canManageExits()) return;
        positions.put(stockCode, position.withCurrentPrice(currentPrice));
        if (currentPrice > position.stopLossPrice() || !stopPending.add(stockCode)) return;

        audit.log(
                "STOP_LOSS_TRIGGERED",
                null,
                stockCode
                        + " reached stop-loss "
                        + String.format("%,d", position.stopLossPrice())
                        + " (tick "
                        + String.format("%,d", currentPrice)
                        + ")");
        websocket.publishEvent("order", "손절 조건 도달: " + stockCode);

        List<KiwoomTradeProposal> takeProfitOrders = openTakeProfitOrders(stockCode);
        if (takeProfitOrders.isEmpty()) {
            submitConfirmedStopOrders(List.of(position));
            return;
        }
        for (KiwoomTradeProposal order : takeProfitOrders) {
            if (order.getRemainingQuantity() > 0)
                orders.cancel(order.getId(), order.getRemainingQuantity());
        }
    }

    private void ensureTakeProfitOrder(Position position) {
        List<KiwoomTradeProposal> current = openTakeProfitOrders(position.stockCode());
        if (current.size() == 1
                && current.get(0).getLimitPrice() != null
                && current.get(0).getLimitPrice() == position.takeProfitPrice()
                && current.get(0).getRemainingQuantity() == position.sellableQuantity()) return;

        if (!current.isEmpty()) {
            for (KiwoomTradeProposal order : current) {
                if (order.getRemainingQuantity() > 0)
                    orders.cancel(order.getId(), order.getRemainingQuantity());
            }
            return;
        }

        KiwoomTradeProposal order = newExitProposal(position, KiwoomTradeProposal.OrderType.LIMIT);
        order.setLimitPrice(position.takeProfitPrice());
        order.setReason(
                "[EXIT:TAKE_PROFIT] 평단 "
                        + String.format("%,d", position.averagePrice())
                        + " 기준 +"
                        + settings.current().getSwingTakeProfitPercent()
                        + "% 익절 주문");
        proposals.save(order);
        audit.log(
                "TAKE_PROFIT_ORDER_CREATED",
                order.getId(),
                position.stockCode()
                        + " 익절 지정가 "
                        + String.format("%,d", position.takeProfitPrice())
                        + " / "
                        + position.sellableQuantity()
                        + "주");
        KiwoomProposalOrderService.Result result = orders.autoExecute(order.getId());
        if (result.success()) {
            log.info(
                    "[자동매매][익절 지정가 주문 전송] {}({}), 평단={}원, 익절 기준=+{}%, 주문가={}원, 수량={}주, 주문번호={}, 상태=미체결(체결 확인 대기)",
                    position.stockName(),
                    position.stockCode(),
                    String.format("%,d", position.averagePrice()),
                    settings.current().getSwingTakeProfitPercent(),
                    String.format("%,d", position.takeProfitPrice()),
                    position.sellableQuantity(),
                    order.getBrokerOrderNo() == null ? "미확인" : order.getBrokerOrderNo());
        } else {
            log.warn(
                    "[자동매매][익절 지정가 주문 전송 실패] {}({}), 평단={}원, 익절 기준=+{}%, 주문가={}원, 사유={}",
                    position.stockName(),
                    position.stockCode(),
                    String.format("%,d", position.averagePrice()),
                    settings.current().getSwingTakeProfitPercent(),
                    String.format("%,d", position.takeProfitPrice()),
                    result.message());
        }
    }

    private void submitConfirmedStopOrders(Collection<Position> refreshed) {
        for (Position position : refreshed) {
            String code = position.stockCode();
            if (!stopPending.contains(code) || stopSubmitted.contains(code)) continue;
            if (!openTakeProfitOrders(code).isEmpty() || hasOtherOpenSell(code)) continue;

            KiwoomTradeProposal order =
                    newExitProposal(position, KiwoomTradeProposal.OrderType.MARKET);
            order.setReason(
                    "[EXIT:STOP_LOSS] 평단 "
                            + String.format("%,d", position.averagePrice())
                            + " 기준 -"
                            + settings.current().getSwingStopLossPercent()
                            + "% 손절 시장가 청산");
            proposals.save(order);
            audit.log("STOP_LOSS_ORDER_CREATED", order.getId(), order.getReason());
            KiwoomProposalOrderService.Result result = orders.autoExecute(order.getId());
            stopSubmitted.add(code);
            if (!result.success()) {
                audit.log("STOP_LOSS_ORDER_NOT_SUBMITTED", order.getId(), result.message());
                websocket.publishEvent("error", "손절 주문 전송 실패: " + code + " (수동 확인 필요)");
            }
        }
    }

    private KiwoomTradeProposal newExitProposal(
            Position position, KiwoomTradeProposal.OrderType orderType) {
        KiwoomTradeProposal order = new KiwoomTradeProposal();
        order.setRun(newExitRun());
        order.setAction(KiwoomTradeProposal.Action.SELL);
        order.setStockCode(position.stockCode());
        order.setStockName(position.stockName());
        order.setQuantity(position.sellableQuantity());
        order.setOrderType(orderType);
        order.setConfidence(100);
        order.setStopLossPrice(position.stopLossPrice());
        order.setTakeProfitPrice(position.takeProfitPrice());
        order.setGuardFlags("");
        return order;
    }

    private KiwoomStrategyRun newExitRun() {
        KiwoomStrategyRun run = new KiwoomStrategyRun();
        run.setTriggeredBy(KiwoomStrategyRun.TriggeredBy.RISK);
        run.setStatus(KiwoomStrategyRun.Status.SUCCESS);
        run.setProviderName("POSITION_EXIT_MANAGER");
        run.setModel("RULE_BASED");
        run.setMarketView("평단 기준 익절 지정가 및 실시간 손절 관리");
        return runs.save(run);
    }

    private boolean isAutomatedPosition(KiwoomTradeService.Holding holding) {
        return proposals.existsByStockCodeAndActionAndStatusIn(
                holding.code(),
                KiwoomTradeProposal.Action.BUY,
                List.of(
                        KiwoomTradeProposal.Status.PARTIALLY_FILLED,
                        KiwoomTradeProposal.Status.FILLED));
    }

    private List<KiwoomTradeProposal> openTakeProfitOrders(String stockCode) {
        return proposals.findByStatusIn(OPEN_SELL_STATUSES).stream()
                .filter(
                        proposal ->
                                proposal.getAction() == KiwoomTradeProposal.Action.SELL
                                        && stockCode.equals(proposal.getStockCode())
                                        && proposal.getReason() != null
                                        && proposal.getReason().startsWith("[EXIT:TAKE_PROFIT]"))
                .toList();
    }

    private boolean hasOtherOpenSell(String stockCode) {
        return proposals.findByStatusIn(OPEN_SELL_STATUSES).stream()
                .anyMatch(
                        proposal ->
                                proposal.getAction() == KiwoomTradeProposal.Action.SELL
                                        && stockCode.equals(proposal.getStockCode())
                                        && (proposal.getReason() == null
                                                || !proposal.getReason()
                                                        .startsWith("[EXIT:TAKE_PROFIT]")));
    }

    private Position toPosition(KiwoomTradeService.Holding holding) {
        double stopPercent = settings.current().getSwingStopLossPercent();
        double takeProfitPercent = settings.current().getSwingTakeProfitPercent();
        long stop = roundDown(holding.avgPrice() * (100 - stopPercent) / 100.0);
        long takeProfit = roundUp(holding.avgPrice() * (100 + takeProfitPercent) / 100.0);
        return new Position(
                holding.code(),
                holding.name(),
                holding.sellable(),
                holding.avgPrice(),
                holding.curPrice(),
                stop,
                takeProfit);
    }

    private long roundUp(double raw) {
        long price = Math.max(1L, (long) Math.ceil(raw));
        long tick = KiwoomPriceRules.tickSize(price, "KRX");
        return ((price + tick - 1) / tick) * tick;
    }

    private long roundDown(double raw) {
        long price = Math.max(1L, (long) Math.floor(raw));
        long tick = KiwoomPriceRules.tickSize(price, "KRX");
        return (price / tick) * tick;
    }

    private boolean canManageExits() {
        return props.getStrategy().isEnabled()
                && props.isConfigured()
                && state.isAutoTrading()
                && !state.isEmergencyStopped()
                && settings.current().isAutoExecute()
                && settings.current().isRiskLoopEnabled();
    }

    private record Position(
            String stockCode,
            String stockName,
            int sellableQuantity,
            long averagePrice,
            long currentPrice,
            long stopLossPrice,
            long takeProfitPrice) {
        Position withCurrentPrice(long price) {
            return new Position(
                    stockCode,
                    stockName,
                    sellableQuantity,
                    averagePrice,
                    price,
                    stopLossPrice,
                    takeProfitPrice);
        }
    }
}
