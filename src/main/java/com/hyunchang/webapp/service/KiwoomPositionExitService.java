package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.entity.KiwoomStrategyRun;
import com.hyunchang.webapp.entity.KiwoomTradeProposal;
import com.hyunchang.webapp.repository.KiwoomStrategyRunRepository;
import com.hyunchang.webapp.repository.KiwoomTradeProposalRepository;
import com.hyunchang.webapp.service.kiwoom.KiwoomAutoTradeState;
import com.hyunchang.webapp.service.kiwoom.KiwoomWebsocketClient;
import com.hyunchang.webapp.util.KiwoomMarketHours;
import com.hyunchang.webapp.util.KiwoomPriceRules;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
    private final Map<String, ExitTrigger> pendingExits = new ConcurrentHashMap<>();
    private final Set<String> exitSubmitted = ConcurrentHashMap.newKeySet();
    private final Set<String> exitWaitingForSellableLogged = ConcurrentHashMap.newKeySet();
    private final Map<String, LocalDateTime> exitTriggeredAt = new ConcurrentHashMap<>();
    private final Set<String> exitTransitionDelayLogged = ConcurrentHashMap.newKeySet();
    private volatile LocalDate preMarketPreparedDate;

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
    public void refreshPositions(String source) {
        LocalDate today = LocalDate.now(KiwoomMarketHours.KST);
        refreshPositions(source, KiwoomMarketHours.isOpen() && today.equals(preMarketPreparedDate));
    }

    /** 설정 화면에서 저장한 청산 규칙을 현재 보유 종목에 다시 적용한다. 장중에는 기존 익절 주문을 취소·재등록하고 최대 보유기간과 손절 조건도 즉시 다시 판단한다. */
    public SettingsApplyResult applyChangedSettings() {
        if (!canManageExits()) {
            log.info(
                    "[자동매매][변경 설정 적용 대기] 사유=자동 주문 또는 청산 감시가 꺼져 있어 기존 주문을 변경하지 않음, 처리=기능을 켜거나 다음 자동매매 시작 시 새 설정 적용");
            return new SettingsApplyResult(false, false, "자동 주문 또는 청산 감시가 꺼져 있습니다.");
        }
        if (!KiwoomMarketHours.isOpen()) {
            boolean refreshed = refreshPositions("SETTINGS_CHANGED", false);
            log.info(
                    "[자동매매][변경 설정 저장 완료] 적용 시점=다음 정규장 주문 시작, 보유 종목 복구={}, 처리=장 시작 시 새 익절률·손절률·최대 보유기간 적용",
                    refreshed ? "완료" : "실패");
            return new SettingsApplyResult(refreshed, false, "다음 정규장 주문 시작 시 적용됩니다.");
        }

        boolean ordersReady = refreshPositions("SETTINGS_CHANGED", true);
        if (ordersReady) {
            log.info("[자동매매][변경 설정 즉시 적용 완료] 보유={}종목, 처리=익절률·손절률·최대 보유기간 재평가 완료", positions.size());
            return new SettingsApplyResult(true, true, "현재 보유 종목에 즉시 적용했습니다.");
        }
        log.info("[자동매매][변경 설정 적용 중] 보유={}종목, 처리=기존 익절 주문 취소 확인 후 새 설정 가격으로 재등록", positions.size());
        return new SettingsApplyResult(true, false, "기존 익절 주문 취소 확인 후 새 가격으로 재등록합니다.");
    }

    /** 자동주문을 완전히 멈출 때 시스템이 전송한 미체결 매수·매도 주문도 함께 취소한다. */
    public synchronized PauseResult pauseExitManagement() {
        int requested = 0;
        int failed = 0;
        for (KiwoomTradeProposal order :
                proposals.findByStatusIn(
                        List.of(
                                KiwoomTradeProposal.Status.ORDERED,
                                KiwoomTradeProposal.Status.PARTIALLY_FILLED))) {
            if (order.getRemainingQuantity() <= 0
                    || order.getBrokerOrderNo() == null
                    || order.getBrokerOrderNo().isBlank()) continue;
            KiwoomProposalOrderService.Result result =
                    orders.cancel(order.getId(), order.getRemainingQuantity());
            if (result.success()) requested++;
            else failed++;
        }
        pendingExits.clear();
        exitTriggeredAt.clear();
        exitTransitionDelayLogged.clear();
        exitWaitingForSellableLogged.clear();
        log.info(
                "[자동매매][자동주문 완전 중지] 미체결 자동주문 취소 요청={}건, 실패={}건, 처리=신규 매수·익절·손절·보유기간 청산 중지",
                requested,
                failed);
        return new PauseResult(requested, failed);
    }

    /** 자동주문 재개 시 현재 잔고와 새 손익률을 다시 계산하고 장중이면 즉시 청산 관리를 복구한다. */
    public synchronized boolean resumeExitManagement() {
        boolean marketOpen = KiwoomMarketHours.isOpen();
        boolean ready = refreshPositions("AUTOMATION_RESUMED", marketOpen);
        log.info(
                "[자동매매][자동주문 재개] 보유={}종목, 장중={}, 처리={}",
                positions.size(),
                marketOpen ? "예" : "아니오",
                marketOpen
                        ? "현재 설정으로 익절·손절·최대 보유기간 재계산 및 주문 복구"
                        : "현재 설정으로 보유 종목 복구, 다음 정규장 시작 시 주문 실행");
        return ready;
    }

    synchronized boolean refreshPositions(String source, boolean manageOrders) {
        if (!canManageExits()) return false;
        try {
            JsonNode balance = trade.getBalance().block(Duration.ofSeconds(10));
            accountHoldings.syncBalance(balance, source);
            List<KiwoomTradeService.Holding> holdings = trade.parseHoldings(balance);
            Map<String, Position> refreshed = new HashMap<>();
            for (KiwoomTradeService.Holding holding : holdings) {
                if (holding.avgPrice() <= 0 || holding.quantity() <= 0) continue;
                Position position = toPosition(holding);
                refreshed.put(position.stockCode(), position);
            }
            positions.clear();
            positions.putAll(refreshed);
            pendingExits.keySet().retainAll(refreshed.keySet());
            exitSubmitted.retainAll(refreshed.keySet());
            exitSubmitted.removeIf(code -> !hasOpenForcedExit(code));
            exitWaitingForSellableLogged.retainAll(refreshed.keySet());
            exitTriggeredAt.keySet().retainAll(refreshed.keySet());
            exitTransitionDelayLogged.retainAll(refreshed.keySet());
            websocket.connectAndSubscribe(new ArrayList<>(refreshed.keySet()));

            boolean ordersReady = true;
            if (manageOrders) {
                // 익절 주문이 전 수량을 예약하면 키움 잔고의 매도 가능 수량은 0이 된다. 그래도
                // 보유 수량과 현재가를 기준으로 손절 감시는 계속해야 한다. 재시작·실시간 연결
                // 끊김 상황에서도 잔고 현재가가 이미 손절가 이하라면 즉시 익절 취소 절차를 시작한다.
                for (Position position : refreshed.values()) {
                    if (position.stopLossPrice() > 0
                            && position.currentPrice() > 0
                            && position.currentPrice() <= position.stopLossPrice())
                        handlePriceTick(position.stockCode(), position.currentPrice());
                }

                for (Position position : refreshed.values()) triggerTimeExitIfDue(position);

                for (Position position : refreshed.values()) {
                    if (!pendingExits.containsKey(position.stockCode())
                            && !exitSubmitted.contains(position.stockCode()))
                        ordersReady &= ensureTakeProfitOrder(position);
                }
                submitConfirmedExitOrders(refreshed.values());
            }
            return !manageOrders || ordersReady;
        } catch (Exception e) {
            if (source.startsWith("PRE_MARKET") || source.startsWith("MARKET_OPEN"))
                log.warn(
                        "[자동매매][보유 종목 복구 실패] 확인 시점={}, 사유={}",
                        source,
                        e.getMessage() == null ? "알 수 없음" : e.getMessage());
            return false;
        }
    }

    boolean isExitManagementEnabled() {
        return canManageExits();
    }

    boolean preparePositionsBeforeMarketOpen(String executionReason, String refreshSource) {
        boolean prepared = refreshPositions(refreshSource, false);
        if (prepared) {
            preMarketPreparedDate = LocalDate.now(KiwoomMarketHours.KST);
            log.info(
                    "[자동매매][보유 종목 사전 준비 완료] 실행={}, 보유={}종목, 처리=주문 전송 없이 잔고 복구 및 실시간 구독 준비",
                    executionReason,
                    positions.size());
        }
        return prepared;
    }

    boolean startExitOrdersAtMarketOpen(String executionReason, String refreshSource) {
        LocalDate today = LocalDate.now(KiwoomMarketHours.KST);
        if (!today.equals(preMarketPreparedDate)) return false;
        boolean started = refreshPositions(refreshSource, true);
        if (started)
            log.info(
                    "[자동매매][청산 관리 시작] 실행={}, 보유={}종목, 처리=익절 지정가 주문 확인 및 실시간 손절 감시 시작",
                    executionReason,
                    positions.size());
        return started;
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

    /**
     * 익절 취소는 확인됐지만 키움 잔고의 매도 가능 수량 반영이 늦는 경우, 손절 감지 후 60초 동안 2초 간격으로 잔고를 다시 확인해 복구 즉시 시장가 손절을 전송한다.
     */
    @Scheduled(fixedDelay = 2000, initialDelay = 2000)
    public void retryPendingStopTransition() {
        if (!KiwoomMarketHours.isOpen()) return;
        retryPendingStopTransitionNow();
    }

    void retryPendingStopTransitionNow() {
        if (!canManageExits() || pendingExits.isEmpty()) return;

        LocalDateTime now = LocalDateTime.now();
        boolean needsRefresh = false;
        for (String code : List.copyOf(pendingExits.keySet())) {
            if (exitSubmitted.contains(code) || !openTakeProfitOrders(code).isEmpty()) continue;
            LocalDateTime triggeredAt = exitTriggeredAt.get(code);
            boolean insideFastWindow =
                    triggeredAt == null
                            || Duration.between(triggeredAt, now).compareTo(Duration.ofSeconds(60))
                                    <= 0;
            if (insideFastWindow) {
                needsRefresh = true;
            } else if (exitTransitionDelayLogged.add(code)) {
                ExitTrigger trigger = pendingExits.get(code);
                log.error(
                        "[자동매매][청산 전환 지연] 종목={}, 청산 사유={}, 60초 동안 매도 가능 수량이 복구되지 않아 일반 안전 점검으로 계속 확인합니다. 키움 주문·잔고를 수동 확인해 주세요.",
                        code,
                        trigger == null ? "미확인" : trigger.label());
                websocket.publishEvent("error", "청산 전환 지연: " + code + " (수동 확인 필요)");
            }
        }
        if (needsRefresh) refreshPositions("STOP_TRANSITION_RECHECK", true);
    }

    /** Invoked by the order synchronizer only when a broker order actually changed state. */
    public void onOrderStateChanged() {
        refreshPositions("ORDER_STATE_CHANGED", KiwoomMarketHours.isOpen());
    }

    /** 주문 동기화 서비스가 손절 전환 중인 익절 취소 건만 빠르게 조회할 때 사용한다. */
    boolean isStopTransitionPending(String stockCode) {
        return stockCode != null
                && pendingExits.get(stockCode) == ExitTrigger.STOP_LOSS
                && !exitSubmitted.contains(stockCode);
    }

    private void onPriceTick(String stockCode, long currentPrice) {
        if (!KiwoomMarketHours.isOpen()) return;
        handlePriceTick(stockCode, currentPrice);
    }

    synchronized void handlePriceTick(String stockCode, long currentPrice) {
        Position position = positions.get(stockCode);
        if (position == null || currentPrice <= 0 || !canManageExits()) return;
        positions.put(stockCode, position.withCurrentPrice(currentPrice));
        if (position.stopLossPrice() <= 0
                || currentPrice > position.stopLossPrice()
                || pendingExits.putIfAbsent(stockCode, ExitTrigger.STOP_LOSS) != null) return;
        exitTriggeredAt.putIfAbsent(stockCode, LocalDateTime.now());

        audit.log(
                "STOP_LOSS_TRIGGERED",
                null,
                stockCode
                        + " 손절 기준 "
                        + String.format("%,d", position.stopLossPrice())
                        + "원 도달 (현재가 "
                        + String.format("%,d", currentPrice)
                        + "원)");
        log.warn(
                "[자동매매][손절 조건 감지] {}({}), 평단={}원, 손절 기준=-{}%, 손절가={}원, 현재가={}원, 처리=기존 익절 주문 취소 후 시장가 청산",
                position.stockName(),
                position.stockCode(),
                String.format("%,d", position.averagePrice()),
                settings.current().getSwingStopLossPercent(),
                String.format("%,d", position.stopLossPrice()),
                String.format("%,d", currentPrice));
        websocket.publishEvent("order", "손절 조건 도달: " + stockCode);

        cancelTakeProfitForExit(position, ExitTrigger.STOP_LOSS);
    }

    private void triggerTimeExitIfDue(Position position) {
        long heldTradingDays = engineHoldingTradingDays(position.stockCode());
        int maxHoldingDays = settings.current().getSwingMaxHoldingDays();
        if (heldTradingDays <= maxHoldingDays
                || pendingExits.putIfAbsent(position.stockCode(), ExitTrigger.TIME_EXIT) != null)
            return;
        exitTriggeredAt.putIfAbsent(position.stockCode(), LocalDateTime.now());
        audit.log(
                "MAX_HOLDING_PERIOD_TRIGGERED",
                null,
                position.stockCode()
                        + " 보유 "
                        + heldTradingDays
                        + "거래일 / 최대 "
                        + maxHoldingDays
                        + "거래일");
        log.warn(
                "[자동매매][최대 보유기간 도달] {}({}), 보유={}거래일, 설정={}거래일, 처리=기존 익절 주문 취소 후 시장가 청산",
                position.stockName(),
                position.stockCode(),
                heldTradingDays,
                maxHoldingDays);
        websocket.publishEvent("order", "최대 보유기간 도달: " + position.stockCode());
        cancelTakeProfitForExit(position, ExitTrigger.TIME_EXIT);
    }

    private void cancelTakeProfitForExit(Position position, ExitTrigger trigger) {
        List<KiwoomTradeProposal> takeProfitOrders = openTakeProfitOrders(position.stockCode());
        if (takeProfitOrders.isEmpty()) {
            submitConfirmedExitOrders(List.of(position));
            return;
        }
        for (KiwoomTradeProposal order : takeProfitOrders) {
            if (order.getStatus() == KiwoomTradeProposal.Status.CANCEL_REQUESTED) continue;
            if (order.getRemainingQuantity() <= 0) continue;
            KiwoomProposalOrderService.Result result =
                    orders.cancel(order.getId(), order.getRemainingQuantity());
            if (result.success()) {
                log.warn(
                        "[자동매매][청산 전환] {}({}), 청산 사유={}, 익절 주문번호={}, 취소 수량={}주, 상태=취소 확인 대기",
                        position.stockName(),
                        position.stockCode(),
                        trigger.label(),
                        order.getBrokerOrderNo() == null ? "미확인" : order.getBrokerOrderNo(),
                        order.getRemainingQuantity());
            } else {
                log.error(
                        "[자동매매][청산 전환 실패] {}({}), 청산 사유={}, 단계=익절 주문 취소 요청, 주문번호={}, 실패 사유={}",
                        position.stockName(),
                        position.stockCode(),
                        trigger.label(),
                        order.getBrokerOrderNo() == null ? "미확인" : order.getBrokerOrderNo(),
                        result.message());
                websocket.publishEvent(
                        "error", "익절 주문 취소 실패: " + position.stockCode() + " (수동 확인 필요)");
            }
        }
    }

    private boolean ensureTakeProfitOrder(Position position) {
        List<KiwoomTradeProposal> current = openTakeProfitOrders(position.stockCode());
        if (position.takeProfitPrice() <= 0) {
            if (current.isEmpty()) return true;
            cancelTakeProfitForSettingsChange(position, current, 0);
            return false;
        }
        if (current.size() == 1
                && current.get(0).getLimitPrice() != null
                && current.get(0).getLimitPrice() == position.takeProfitPrice()
                && current.get(0).getRemainingQuantity() == position.holdingQuantity()) return true;

        if (!current.isEmpty()) {
            cancelTakeProfitForSettingsChange(position, current, position.takeProfitPrice());
            return false;
        }
        if (position.sellableQuantity() <= 0) return false;

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
            KiwoomTradeProposal submitted = result.proposal();
            log.info(
                    "[자동매매][익절 지정가 주문 전송] {}({}), 평단={}원, 익절 기준=+{}%, 주문가={}원, 수량={}주, 주문번호={}, 상태=미체결(체결 확인 대기)",
                    position.stockName(),
                    position.stockCode(),
                    String.format("%,d", position.averagePrice()),
                    settings.current().getSwingTakeProfitPercent(),
                    String.format("%,d", position.takeProfitPrice()),
                    position.sellableQuantity(),
                    submitted == null || submitted.getBrokerOrderNo() == null
                            ? "미확인"
                            : submitted.getBrokerOrderNo());
            return true;
        } else {
            KiwoomTradeProposal failed = result.proposal() == null ? order : result.proposal();
            if (failed.getStatus() != KiwoomTradeProposal.Status.ORDER_UNKNOWN) {
                failed.expired("익절 지정가 주문 전송 실패로 재시도 대기: " + result.message());
                proposals.save(failed);
            }
            log.warn(
                    "[자동매매][익절 지정가 주문 전송 실패] {}({}), 평단={}원, 익절 기준=+{}%, 주문가={}원, 사유={}",
                    position.stockName(),
                    position.stockCode(),
                    String.format("%,d", position.averagePrice()),
                    settings.current().getSwingTakeProfitPercent(),
                    String.format("%,d", position.takeProfitPrice()),
                    result.message());
            return false;
        }
    }

    private void cancelTakeProfitForSettingsChange(
            Position position, List<KiwoomTradeProposal> current, long newTakeProfitPrice) {
        for (KiwoomTradeProposal order : current) {
            if (order.getRemainingQuantity() <= 0
                    || order.getStatus() == KiwoomTradeProposal.Status.CANCEL_REQUESTED) continue;
            KiwoomProposalOrderService.Result result =
                    orders.cancel(order.getId(), order.getRemainingQuantity());
            String newPriceLabel =
                    newTakeProfitPrice > 0 ? String.format("%,d원", newTakeProfitPrice) : "사용 안 함";
            if (result.success()) {
                log.info(
                        "[자동매매][익절 주문 설정 변경] {}({}), 기존 주문가={}원, 변경 주문가={}, 수량={}주, 주문번호={}, 상태=기존 주문 취소 확인 대기",
                        position.stockName(),
                        position.stockCode(),
                        order.getLimitPrice() == null
                                ? "미확인"
                                : String.format("%,d", order.getLimitPrice()),
                        newPriceLabel,
                        order.getRemainingQuantity(),
                        order.getBrokerOrderNo() == null ? "미확인" : order.getBrokerOrderNo());
            } else {
                log.warn(
                        "[자동매매][익절 주문 설정 변경 실패] {}({}), 기존 주문가={}원, 변경 주문가={}, 주문번호={}, 실패 사유={}",
                        position.stockName(),
                        position.stockCode(),
                        order.getLimitPrice() == null
                                ? "미확인"
                                : String.format("%,d", order.getLimitPrice()),
                        newPriceLabel,
                        order.getBrokerOrderNo() == null ? "미확인" : order.getBrokerOrderNo(),
                        result.message());
            }
        }
    }

    private void submitConfirmedExitOrders(Collection<Position> refreshed) {
        for (Position position : refreshed) {
            String code = position.stockCode();
            ExitTrigger trigger = pendingExits.get(code);
            if (trigger == null || exitSubmitted.contains(code)) continue;
            if (!openTakeProfitOrders(code).isEmpty() || hasOtherOpenSell(code)) continue;
            if (position.sellableQuantity() <= 0) {
                if (exitWaitingForSellableLogged.add(code)) {
                    log.warn(
                            "[자동매매][청산 전환 대기] {}({}), 청산 사유={}, 보유={}주, 매도 가능=0주, 상태=익절 주문 취소 확인 후 매도 가능 수량 복구 대기",
                            position.stockName(),
                            position.stockCode(),
                            trigger.label(),
                            position.holdingQuantity());
                }
                continue;
            }
            exitWaitingForSellableLogged.remove(code);

            KiwoomTradeProposal order =
                    newExitProposal(position, KiwoomTradeProposal.OrderType.MARKET);
            order.setReason(exitReason(trigger, position));
            proposals.save(order);
            audit.log(trigger.auditOrderEvent(), order.getId(), order.getReason());
            KiwoomProposalOrderService.Result result = orders.autoExecute(order.getId());
            if (result.success()) {
                exitSubmitted.add(code);
                exitTriggeredAt.remove(code);
                exitTransitionDelayLogged.remove(code);
                KiwoomTradeProposal submitted = result.proposal();
                log.warn(
                        "[자동매매][{} 시장가 주문 전송] {}({}), 수량={}주, 주문번호={}, 근거={}",
                        trigger.label(),
                        position.stockName(),
                        position.stockCode(),
                        position.sellableQuantity(),
                        submitted == null || submitted.getBrokerOrderNo() == null
                                ? "미확인"
                                : submitted.getBrokerOrderNo(),
                        order.getReason());
            } else {
                audit.log(trigger.auditFailureEvent(), order.getId(), result.message());
                log.error(
                        "[자동매매][{} 시장가 주문 전송 실패] {}({}), 수량={}주, 사유={}",
                        trigger.label(),
                        position.stockName(),
                        position.stockCode(),
                        position.sellableQuantity(),
                        result.message());
                websocket.publishEvent(
                        "error", trigger.label() + " 주문 전송 실패: " + code + " (수동 확인 필요)");
            }
        }
    }

    private String exitReason(ExitTrigger trigger, Position position) {
        if (trigger == ExitTrigger.TIME_EXIT)
            return "[EXIT:TIME_EXIT] 최대 보유기간 "
                    + settings.current().getSwingMaxHoldingDays()
                    + "거래일 초과 시장가 청산";
        return "[EXIT:STOP_LOSS] 평단 "
                + String.format("%,d", position.averagePrice())
                + " 기준 -"
                + settings.current().getSwingStopLossPercent()
                + "% 손절 시장가 청산";
    }

    private long engineHoldingTradingDays(String code) {
        LocalDateTime observedAt = accountHoldings.positionOpenedAt(code).orElse(null);
        if (observedAt != null) return tradingDaysSince(observedAt.toLocalDate());
        return proposals
                .findFirstByStockCodeAndActionAndStatusOrderByCreatedAtDesc(
                        code, KiwoomTradeProposal.Action.BUY, KiwoomTradeProposal.Status.FILLED)
                .filter(proposal -> proposal.getCreatedAt() != null)
                .map(
                        proposal -> {
                            return tradingDaysSince(proposal.getCreatedAt().toLocalDate());
                        })
                .orElse(0L);
    }

    private long tradingDaysSince(LocalDate opened) {
        LocalDate today = LocalDate.now(KiwoomMarketHours.KST);
        if (opened == null || !opened.isBefore(today)) return 0L;
        return opened.plusDays(1)
                .datesUntil(today.plusDays(1))
                .filter(KiwoomMarketHours::isTradingDay)
                .count();
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

    private boolean isTakeProfit(KiwoomTradeProposal proposal) {
        return proposal.getAction() == KiwoomTradeProposal.Action.SELL
                && proposal.getReason() != null
                && proposal.getReason().startsWith("[EXIT:TAKE_PROFIT]");
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

    private boolean hasOpenForcedExit(String stockCode) {
        return proposals.findByStatusIn(OPEN_SELL_STATUSES).stream()
                .anyMatch(
                        proposal ->
                                proposal.getAction() == KiwoomTradeProposal.Action.SELL
                                        && stockCode.equals(proposal.getStockCode())
                                        && proposal.getReason() != null
                                        && (proposal.getReason().startsWith("[EXIT:STOP_LOSS]")
                                                || proposal.getReason()
                                                        .startsWith("[EXIT:TIME_EXIT]")));
    }

    private Position toPosition(KiwoomTradeService.Holding holding) {
        double stopPercent = settings.current().getSwingStopLossPercent();
        double takeProfitPercent = settings.current().getSwingTakeProfitPercent();
        long stop =
                stopPercent <= 0 ? 0 : roundDown(holding.avgPrice() * (100 - stopPercent) / 100.0);
        long takeProfit =
                takeProfitPercent <= 0
                        ? 0
                        : roundUp(holding.avgPrice() * (100 + takeProfitPercent) / 100.0);
        return new Position(
                holding.code(),
                holding.name(),
                holding.quantity(),
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

    private enum ExitTrigger {
        STOP_LOSS("손절", "STOP_LOSS_ORDER_CREATED", "STOP_LOSS_ORDER_NOT_SUBMITTED"),
        TIME_EXIT("보유기간 청산", "TIME_EXIT_ORDER_CREATED", "TIME_EXIT_ORDER_NOT_SUBMITTED");

        private final String label;
        private final String auditOrderEvent;
        private final String auditFailureEvent;

        ExitTrigger(String label, String auditOrderEvent, String auditFailureEvent) {
            this.label = label;
            this.auditOrderEvent = auditOrderEvent;
            this.auditFailureEvent = auditFailureEvent;
        }

        String label() {
            return label;
        }

        String auditOrderEvent() {
            return auditOrderEvent;
        }

        String auditFailureEvent() {
            return auditFailureEvent;
        }
    }

    private record Position(
            String stockCode,
            String stockName,
            int holdingQuantity,
            int sellableQuantity,
            long averagePrice,
            long currentPrice,
            long stopLossPrice,
            long takeProfitPrice) {
        Position withCurrentPrice(long price) {
            return new Position(
                    stockCode,
                    stockName,
                    holdingQuantity,
                    sellableQuantity,
                    averagePrice,
                    price,
                    stopLossPrice,
                    takeProfitPrice);
        }
    }

    public record SettingsApplyResult(
            boolean balanceRefreshed, boolean completed, String message) {}

    public record PauseResult(int cancellationRequested, int cancellationFailed) {}
}
