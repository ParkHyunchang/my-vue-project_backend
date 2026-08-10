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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Maintains one or two (2단계 분할 익절 시) broker-side take-profit limit orders per automated
 * position and watches stop-loss levels from 0B real-time ticks. Tick comparisons are
 * intentionally memory-only: balance is read only at startup, after an order state change, or
 * while WebSocket fallback protection is active.
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
    // 익절 주문 취소가 키움 잔고의 매도가능수량에 반영되기까지 몇 초가 걸린다. 요청 응답이 지나치게
    // 길어지지 않는 선에서 재확인하고, 남은 건은 2초 주기 청산 재시도 루프에 넘긴다.
    private static final int LIQUIDATION_ATTEMPTS = 4;
    private static final long LIQUIDATION_RETRY_INTERVAL_MS = 1500;
    private static final long PRICE_LIMIT_LOOKUP_RETRY_MS = 60_000;

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
    private final Map<String, CachedDailyPriceLimit> dailyPriceLimits = new ConcurrentHashMap<>();
    private final Set<String> priceLimitLookupFailureLogged = ConcurrentHashMap.newKeySet();
    private final Set<String> takeProfitUpperLimitWaitLogged = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean liquidationInProgress = new AtomicBoolean();
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
        websocket.addPriceListener(
                tick -> {
                    onPriceTick(tick.stockCode(), tick.price());
                    if (positions.containsKey(tick.stockCode())) websocket.publishPriceTick(tick);
                });
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
                    orders.cancel(
                            order.getId(),
                            order.getRemainingQuantity(),
                            "자동주문 완전 중지로 미체결 주문을 취소합니다.");
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

    /**
     * 화면에서 누른 수동 시장가 청산이다. 보유 전량을 예약 중인 익절 지정가 주문을 먼저 취소하고, 키움 잔고의 매도가능수량이 돌아오는 대로 시장가로 전량 매도한다.
     * 자동 주문 스위치와 무관하게 동작한다.
     *
     * @param requestedCodes 비어 있으면 보유 전 종목
     */
    public LiquidationResult liquidate(Collection<String> requestedCodes) {
        String blocked = liquidationBlockReason();
        if (blocked != null) return LiquidationResult.blocked(blocked);
        if (!liquidationInProgress.compareAndSet(false, true))
            return LiquidationResult.blocked("이전 청산 요청이 아직 처리 중입니다. 잠시 후 다시 시도해 주세요.");
        try {
            return runLiquidation(requestedCodes);
        } finally {
            liquidationInProgress.set(false);
        }
    }

    private LiquidationResult runLiquidation(Collection<String> requestedCodes) {
        List<KiwoomTradeService.Holding> targets;
        try {
            targets = liquidationTargets(requestedCodes);
        } catch (Exception e) {
            return LiquidationResult.blocked("키움 잔고를 조회하지 못해 청산을 시작하지 않았습니다: " + message(e));
        }
        if (targets.isEmpty())
            return LiquidationResult.blocked(
                    requestedCodes == null || requestedCodes.isEmpty()
                            ? "청산할 보유 종목이 없습니다."
                            : "요청한 종목이 현재 키움 잔고에 없습니다.");

        String targetLabel =
                targets.stream()
                        .map(holding -> holding.name() + "(" + holding.code() + ")")
                        .collect(Collectors.joining(", "));
        log.warn(
                "[자동매매][수동 청산 요청] 대상={}종목, 목록={}, 처리=미체결 매도 주문 취소 후 매도가능수량이 돌아오는 대로 시장가 전량 매도",
                targets.size(),
                targetLabel);
        audit.log("MANUAL_LIQUIDATION_REQUESTED", null, "수동 청산 요청: " + targetLabel);
        websocket.publishEvent("order", "수동 청산 요청: " + targets.size() + "종목");

        Map<String, Liquidation> pending = new LinkedHashMap<>();
        for (KiwoomTradeService.Holding holding : targets) {
            Liquidation item = new Liquidation(holding);
            pending.put(holding.code(), item);
            // 자동 청산 루프가 방금 취소한 익절 주문을 다시 걸지 않도록 청산 대기로 표시한다.
            pendingExits.put(holding.code(), ExitTrigger.MANUAL_EXIT);
            exitTriggeredAt.putIfAbsent(holding.code(), LocalDateTime.now());
            exitSubmitted.remove(holding.code());
            item.canceledOrders = cancelOpenSellOrders(holding);
        }

        for (int attempt = 0; attempt < LIQUIDATION_ATTEMPTS; attempt++) {
            if (attempt > 0 && !sleepBetweenLiquidationAttempts()) break;
            Map<String, KiwoomTradeService.Holding> latest;
            try {
                latest = holdingsByCode();
            } catch (Exception e) {
                continue;
            }
            boolean waiting = false;
            for (Liquidation item : pending.values()) {
                if (item.settled()) continue;
                KiwoomTradeService.Holding holding = latest.get(item.stockCode());
                if (holding == null || holding.quantity() <= 0) {
                    item.markAlreadyFlat();
                    continue;
                }
                item.holding = holding;
                if (holding.sellable() <= 0) {
                    waiting = true;
                    continue;
                }
                submitManualExit(item);
                if (!item.settled()) waiting = true;
            }
            if (!waiting) break;
        }

        List<LiquidationItem> items = pending.values().stream().map(Liquidation::toItem).toList();
        long submitted = items.stream().filter(item -> "SUBMITTED".equals(item.status())).count();
        long waiting = items.stream().filter(item -> "WAITING_SELLABLE".equals(item.status())).count();
        long failed = items.stream().filter(item -> "FAILED".equals(item.status())).count();
        log.warn(
                "[자동매매][수동 청산 결과] 대상={}종목, 시장가 매도 전송={}건, 매도가능수량 대기={}건, 실패={}건",
                items.size(),
                submitted,
                waiting,
                failed);
        // 남은 대기 건은 자동주문이 켜져 있으면 2초 주기 청산 재시도 루프가 이어받는다. 자동주문이
        // 꺼져 있으면 refreshPositions가 그대로 빠져나가므로 보유현황 스냅샷만이라도 최신으로 맞춘다.
        if (canManageExits()) refreshPositions("MANUAL_LIQUIDATION", KiwoomMarketHours.isOpen());
        else accountHoldings.sync("MANUAL_LIQUIDATION");
        return new LiquidationResult(true, liquidationSummary(submitted, waiting, failed), items);
    }

    private String liquidationSummary(long submitted, long waiting, long failed) {
        StringBuilder summary = new StringBuilder("시장가 매도 " + submitted + "건을 전송했습니다.");
        if (waiting > 0)
            summary.append(" 매도가능수량 복구 대기 ")
                    .append(waiting)
                    .append("건은 취소 확인 후 자동으로 재시도합니다")
                    .append(canManageExits() ? "." : " (자동주문이 꺼져 있어 다시 눌러야 합니다).");
        if (failed > 0) summary.append(" 실패 ").append(failed).append("건은 키움 주문을 확인해 주세요.");
        return summary.toString();
    }

    private String liquidationBlockReason() {
        if (!props.isConfigured()) return "키움 API 키가 설정되지 않았습니다.";
        if (!props.isTradeEnabled()) return "주문 전송이 비활성화되어 있습니다. KIWOOM_TRADE_ENABLED 설정을 확인하세요.";
        if (state.isEmergencyStopped()) return "안전 자동중지 상태입니다. 자동주문을 다시 시작해 해제한 뒤 청산하세요.";
        if (!KiwoomMarketHours.isOpen()) return "시장가 청산은 정규장(평일 09:00~15:30 KST)에만 보낼 수 있습니다.";
        return null;
    }

    private List<KiwoomTradeService.Holding> liquidationTargets(Collection<String> requestedCodes)
            throws Exception {
        Collection<KiwoomTradeService.Holding> all = holdingsByCode().values();
        if (requestedCodes == null || requestedCodes.isEmpty()) return List.copyOf(all);
        Set<String> wanted = Set.copyOf(requestedCodes);
        return all.stream().filter(holding -> wanted.contains(holding.code())).toList();
    }

    private Map<String, KiwoomTradeService.Holding> holdingsByCode() throws Exception {
        JsonNode balance = trade.getBalance().block(Duration.ofSeconds(10));
        Map<String, KiwoomTradeService.Holding> result = new LinkedHashMap<>();
        for (KiwoomTradeService.Holding holding : trade.parseHoldings(balance))
            if (holding.quantity() > 0) result.put(holding.code(), holding);
        return result;
    }

    /** 청산 전에 해당 종목의 미체결 매도 주문을 모두 정리한다. 익절 지정가뿐 아니라 사람이 낸 매도 주문도 대상이다. */
    private int cancelOpenSellOrders(KiwoomTradeService.Holding holding) {
        int canceled = 0;
        for (KiwoomTradeProposal order : proposals.findByStatusIn(OPEN_SELL_STATUSES)) {
            if (order.getAction() != KiwoomTradeProposal.Action.SELL
                    || !holding.code().equals(order.getStockCode())
                    || order.getRemainingQuantity() <= 0) continue;
            if (order.getStatus() != KiwoomTradeProposal.Status.ORDERED
                    && order.getStatus() != KiwoomTradeProposal.Status.PARTIALLY_FILLED) continue;
            KiwoomProposalOrderService.Result result =
                    orders.cancel(
                            order.getId(),
                            order.getRemainingQuantity(),
                            "수동 시장가 청산을 위해 기존 매도 주문을 취소합니다.");
            if (result.success()) {
                canceled++;
                log.info(
                        "[자동매매][수동 청산 준비] {}({}), 기존 매도 주문번호={}, 취소 수량={}주, 상태=취소 확인 대기",
                        holding.name(),
                        holding.code(),
                        order.getBrokerOrderNo() == null ? "미확인" : order.getBrokerOrderNo(),
                        order.getRemainingQuantity());
            } else if (isNothingLeftToCancel(result.message())) {
                // 키움에 취소할 수량이 없다는 것은 이 주문이 이미 종료됐다는 뜻이다. 로컬 상태만
                // 살아 있으면 매번 취소를 재시도하다 청산이 막히므로 여기서 닫는다.
                order.expired("수동 청산 확인: 키움에 남은 취소 가능 수량이 없어 종료 처리했습니다.");
                proposals.save(order);
                log.info(
                        "[자동매매][수동 청산 준비] {}({}), 기존 매도 주문번호={}, 처리=키움에 남은 취소 가능 수량이 없어 로컬 주문 종료",
                        holding.name(),
                        holding.code(),
                        order.getBrokerOrderNo() == null ? "미확인" : order.getBrokerOrderNo());
            } else {
                log.warn(
                        "[자동매매][수동 청산 준비 실패] {}({}), 기존 매도 주문번호={}, 단계=주문 취소 요청, 사유={}",
                        holding.name(),
                        holding.code(),
                        order.getBrokerOrderNo() == null ? "미확인" : order.getBrokerOrderNo(),
                        result.message());
            }
        }
        return canceled;
    }

    private boolean isNothingLeftToCancel(String message) {
        return message != null && (message.contains("취소가능수량") || message.contains("506550"));
    }

    private void submitManualExit(Liquidation item) {
        Position position = toPosition(item.holding);
        KiwoomTradeProposal order =
                newExitProposal(position, KiwoomTradeProposal.OrderType.MARKET);
        order.setReason(exitReason(ExitTrigger.MANUAL_EXIT, position));
        proposals.save(order);
        audit.log(ExitTrigger.MANUAL_EXIT.auditOrderEvent(), order.getId(), order.getReason());
        KiwoomProposalOrderService.Result result = orders.submitManualExit(order.getId());
        if (result.success()) {
            exitSubmitted.add(item.stockCode());
            exitTriggeredAt.remove(item.stockCode());
            exitTransitionDelayLogged.remove(item.stockCode());
            KiwoomTradeProposal submitted = result.proposal();
            item.markSubmitted(
                    position.sellableQuantity(),
                    submitted == null ? null : submitted.getBrokerOrderNo());
            log.warn(
                    "[자동매매][수동 청산 시장가 주문 전송] {}({}), 수량={}주, 주문번호={}, 근거={}",
                    position.stockName(),
                    position.stockCode(),
                    position.sellableQuantity(),
                    submitted == null || submitted.getBrokerOrderNo() == null
                            ? "미확인"
                            : submitted.getBrokerOrderNo(),
                    order.getReason());
        } else {
            audit.log(
                    ExitTrigger.MANUAL_EXIT.auditFailureEvent(), order.getId(), result.message());
            item.markFailed(result.message());
            log.error(
                    "[자동매매][수동 청산 시장가 주문 전송 실패] {}({}), 수량={}주, 사유={}",
                    position.stockName(),
                    position.stockCode(),
                    position.sellableQuantity(),
                    result.message());
            websocket.publishEvent(
                    "error", "수동 청산 주문 전송 실패: " + item.stockCode() + " (수동 확인 필요)");
        }
    }

    private boolean sleepBetweenLiquidationAttempts() {
        try {
            Thread.sleep(LIQUIDATION_RETRY_INTERVAL_MS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private String message(Exception e) {
        return e.getMessage() == null ? "알 수 없음" : e.getMessage();
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

    /** 장중 상한가 조회만 실패한 종목은 1분 간격으로 잔고와 가격 제한을 다시 확인한다. */
    @Scheduled(fixedDelay = PRICE_LIMIT_LOOKUP_RETRY_MS)
    public void retryFailedPriceLimitLookups() {
        if (!canManageExits() || !KiwoomMarketHours.isOpen()) return;
        LocalDate today = LocalDate.now(KiwoomMarketHours.KST);
        long now = System.currentTimeMillis();
        boolean retryDue =
                dailyPriceLimits.values().stream()
                        .anyMatch(
                                cached ->
                                        today.equals(cached.tradingDate())
                                                && cached.upperPrice() <= 0
                                                && now >= cached.retryAfterEpochMs());
        if (retryDue) refreshPositions("PRICE_LIMIT_RECHECK", true);
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

    /**
     * 주문 동기화 서비스가 빠르게 조회해야 할 익절 취소 건인지 알려준다. 손절과 수동 청산은 취소 확인이 늦어질수록 시장가 매도가 그만큼 밀리므로 1분 정기
     * 동기화를 기다리지 않는다. 보유기간 청산은 급하지 않아 정기 동기화에 맡긴다.
     */
    boolean isStopTransitionPending(String stockCode) {
        if (stockCode == null || exitSubmitted.contains(stockCode)) return false;
        ExitTrigger trigger = pendingExits.get(stockCode);
        return trigger == ExitTrigger.STOP_LOSS || trigger == ExitTrigger.MANUAL_EXIT;
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
                    orders.cancel(
                            order.getId(),
                            order.getRemainingQuantity(),
                            trigger.label() + " 조건 도달로 기존 익절 주문을 취소합니다.");
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

    /**
     * 계획된 tranche(1개 또는 2개)를 현재 열려 있는 익절 주문과 비교해 한 번 호출에 한 가지 조치만
     * 수행한다: 이미 맞는 tranche는 건너뛰고, 어긋난 tranche는 취소만 하고 반환(재생성은 다음 주기),
     * 아직 없는 tranche는 매도가능수량이 허용할 때만 새로 건다. 2차 tranche는 1차 주문이 실제로
     * 매도가능수량을 줄인 게 다음 refreshPositions 주기에 반영된 뒤에야 생성된다.
     */
    private boolean ensureTakeProfitOrder(Position position) {
        List<TakeProfitTranche> plan = position.takeProfitTranches();
        List<KiwoomTradeProposal> current = openTakeProfitOrders(position.stockCode());
        if (plan.isEmpty()) {
            if (current.isEmpty()) return true;
            cancelTakeProfitForSettingsChange(position, current, 0);
            return false;
        }
        for (TakeProfitTranche tranche : plan) {
            List<KiwoomTradeProposal> forTier = ordersForTier(current, tranche.tier());
            if (forTier.size() == 1
                    && forTier.get(0).getLimitPrice() != null
                    && forTier.get(0).getLimitPrice() == tranche.price()
                    && forTier.get(0).getRemainingQuantity() == tranche.quantity()) continue;

            if (!forTier.isEmpty()) {
                cancelTakeProfitForSettingsChange(position, forTier, tranche.price());
                return false;
            }
            if (position.dailyUpperPrice() <= 0) return false;
            if (tranche.price() > position.dailyUpperPrice()) {
                logTakeProfitUpperLimitWait(position, tranche);
                // 당일에는 상한가가 바뀌지 않으므로 정상적인 대기 상태로 본다. 다음 거래일
                // 사전 복구에서 새 상한가를 조회하고 같은 목표가로 다시 판단한다.
                return true;
            }
            if (position.sellableQuantity() < tranche.quantity()) return false;
            return createTakeProfitTranche(position, tranche);
        }
        return true;
    }

    private boolean createTakeProfitTranche(Position position, TakeProfitTranche tranche) {
        String tierLabel = tranche.tier() == 1 ? "1차" : "2차";
        KiwoomTradeProposal order = newExitProposal(position, KiwoomTradeProposal.OrderType.LIMIT);
        order.setQuantity(tranche.quantity());
        order.setLimitPrice(tranche.price());
        order.setTakeProfitPrice(tranche.price());
        order.setReason(
                "[EXIT:TAKE_PROFIT-"
                        + tranche.tier()
                        + "] 평단 "
                        + String.format("%,d", position.averagePrice())
                        + " 기준 +"
                        + tranche.percent()
                        + "% "
                        + tierLabel
                        + " 익절 주문");
        proposals.save(order);
        audit.log(
                "TAKE_PROFIT_ORDER_CREATED",
                order.getId(),
                position.stockCode()
                        + " "
                        + tierLabel
                        + " 익절 지정가 "
                        + String.format("%,d", tranche.price())
                        + " / "
                        + tranche.quantity()
                        + "주");
        KiwoomProposalOrderService.Result result = orders.autoExecute(order.getId());
        if (result.success()) {
            KiwoomTradeProposal submitted = result.proposal();
            log.info(
                    "[자동매매][{} 익절 지정가 주문 전송] {}({}), 평단={}원, 익절 기준=+{}%, 주문가={}원, 수량={}주, 주문번호={}, 상태=미체결(체결 확인 대기)",
                    tierLabel,
                    position.stockName(),
                    position.stockCode(),
                    String.format("%,d", position.averagePrice()),
                    tranche.percent(),
                    String.format("%,d", tranche.price()),
                    tranche.quantity(),
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
                    "[자동매매][{} 익절 지정가 주문 전송 실패] {}({}), 평단={}원, 익절 기준=+{}%, 주문가={}원, 사유={}",
                    tierLabel,
                    position.stockName(),
                    position.stockCode(),
                    String.format("%,d", position.averagePrice()),
                    tranche.percent(),
                    String.format("%,d", tranche.price()),
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
                    orders.cancel(
                            order.getId(),
                            order.getRemainingQuantity(),
                            "익절 기준 설정이 변경되어 기존 익절 주문을 취소합니다.");
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
        if (trigger == ExitTrigger.MANUAL_EXIT)
            return "[EXIT:MANUAL] 관리자 수동 청산 요청 시장가 매도 (평단 "
                    + String.format("%,d", position.averagePrice())
                    + "원, 현재가 "
                    + String.format("%,d", position.currentPrice())
                    + "원)";
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
                                        && proposal.getReason().startsWith("[EXIT:TAKE_PROFIT"))
                .toList();
    }

    /**
     * 1차/2차 분할 익절 주문 중 해당 tier에 태깅된 것만 골라낸다. 태깅 방식 도입 전(예: "[EXIT:TAKE_PROFIT]")에
     * 이미 떠 있던 구버전 주문은 태그가 없어도 1차로 취급해야 설정 변경 시 정상적으로 재조정된다.
     */
    private List<KiwoomTradeProposal> ordersForTier(List<KiwoomTradeProposal> current, int tier) {
        String tag = "[EXIT:TAKE_PROFIT-" + tier + "]";
        return current.stream()
                .filter(
                        proposal ->
                                proposal.getReason() != null
                                        && (proposal.getReason().startsWith(tag)
                                                || (tier == 1
                                                        && proposal.getReason()
                                                                .startsWith("[EXIT:TAKE_PROFIT]"))))
                .toList();
    }

    private boolean isTakeProfit(KiwoomTradeProposal proposal) {
        return proposal.getAction() == KiwoomTradeProposal.Action.SELL
                && proposal.getReason() != null
                && proposal.getReason().startsWith("[EXIT:TAKE_PROFIT");
    }

    private boolean hasOtherOpenSell(String stockCode) {
        return proposals.findByStatusIn(OPEN_SELL_STATUSES).stream()
                .anyMatch(
                        proposal ->
                                proposal.getAction() == KiwoomTradeProposal.Action.SELL
                                        && stockCode.equals(proposal.getStockCode())
                                        && (proposal.getReason() == null
                                                || !proposal.getReason()
                                                        .startsWith("[EXIT:TAKE_PROFIT")));
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
                                                        .startsWith("[EXIT:TIME_EXIT]")
                                                || proposal.getReason()
                                                        .startsWith("[EXIT:MANUAL]")));
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
        List<TakeProfitTranche> tranches =
                planTakeProfitTranches(
                        holding.avgPrice(), takeProfitPercent, holding.quantity(), takeProfit);
        long dailyUpperPrice = tranches.isEmpty() ? 0 : dailyUpperPrice(holding);
        return new Position(
                holding.code(),
                holding.name(),
                holding.quantity(),
                holding.sellable(),
                holding.avgPrice(),
                holding.curPrice(),
                stop,
                takeProfit,
                dailyUpperPrice,
                tranches);
    }

    private long dailyUpperPrice(KiwoomTradeService.Holding holding) {
        LocalDate today = LocalDate.now(KiwoomMarketHours.KST);
        long now = System.currentTimeMillis();
        CachedDailyPriceLimit cached = dailyPriceLimits.get(holding.code());
        if (cached != null
                && today.equals(cached.tradingDate())
                && (cached.upperPrice() > 0 || now < cached.retryAfterEpochMs()))
            return cached.upperPrice();

        String logKey = today + ":" + holding.code();
        try {
            KiwoomTradeService.DailyPriceLimit limit =
                    trade.getDailyPriceLimit(holding.code()).block(Duration.ofSeconds(10));
            long upperPrice = limit == null ? 0 : limit.upperPrice();
            if (upperPrice <= 0)
                throw new IllegalStateException("키움 종목 기본정보에 상한가가 없습니다.");
            dailyPriceLimits.put(
                    holding.code(), new CachedDailyPriceLimit(today, upperPrice, Long.MAX_VALUE));
            priceLimitLookupFailureLogged.remove(logKey);
            return upperPrice;
        } catch (Exception e) {
            dailyPriceLimits.put(
                    holding.code(),
                    new CachedDailyPriceLimit(today, 0, now + PRICE_LIMIT_LOOKUP_RETRY_MS));
            if (priceLimitLookupFailureLogged.add(logKey))
                log.warn(
                        "[자동매매][당일 상한가 조회 실패] {}({}), 처리=익절 지정가 주문 보류 후 1분 뒤 재시도, 사유={}",
                        holding.name(),
                        holding.code(),
                        e.getMessage() == null ? "알 수 없음" : e.getMessage());
            return 0;
        }
    }

    private void logTakeProfitUpperLimitWait(Position position, TakeProfitTranche tranche) {
        LocalDate today = LocalDate.now(KiwoomMarketHours.KST);
        String logKey = today + ":" + position.stockCode() + ":" + tranche.tier();
        if (!takeProfitUpperLimitWaitLogged.add(logKey)) return;
        log.info(
                "[자동매매][익절 주문 당일 보류] {}({}), {}차 목표가={}원, 당일 상한가={}원, 처리=목표가를 낮추지 않고 다음 거래일 상한가 재조회 후 재시도",
                position.stockName(),
                position.stockCode(),
                tranche.tier(),
                String.format("%,d", tranche.price()),
                String.format("%,d", position.dailyUpperPrice()));
    }

    /**
     * 보유수량이 1주뿐이거나 2차 익절률이 꺼져 있으면(0) 지금까지처럼 1차 가격에 전량을 배정한 단일
     * tranche를 돌려준다. 2차 익절가가 반올림 tick 때문에 1차 이하로 겹치면 분할 자체가 무의미하므로
     * 같은 방식으로 단일 tranche로 안전하게 폴백한다.
     */
    private List<TakeProfitTranche> planTakeProfitTranches(
            long avgPrice, double tier1Percent, int holdingQuantity, long tier1Price) {
        if (tier1Price <= 0) return List.of();
        double tier2Percent = settings.current().getSwingTakeProfitPercent2();
        if (holdingQuantity <= 1 || tier2Percent <= 0)
            return List.of(new TakeProfitTranche(1, tier1Price, holdingQuantity, tier1Percent));

        long tier2Price = roundUp(avgPrice * (100 + tier2Percent) / 100.0);
        if (tier2Price <= tier1Price)
            return List.of(new TakeProfitTranche(1, tier1Price, holdingQuantity, tier1Percent));

        double splitPercent = settings.current().getSwingTakeProfitSplitPercent();
        int tier1Qty =
                Math.max(
                        1,
                        Math.min(
                                holdingQuantity - 1,
                                (int) Math.round(holdingQuantity * splitPercent / 100.0)));
        int tier2Qty = holdingQuantity - tier1Qty;
        return List.of(
                new TakeProfitTranche(1, tier1Price, tier1Qty, tier1Percent),
                new TakeProfitTranche(2, tier2Price, tier2Qty, tier2Percent));
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
        TIME_EXIT("보유기간 청산", "TIME_EXIT_ORDER_CREATED", "TIME_EXIT_ORDER_NOT_SUBMITTED"),
        MANUAL_EXIT("수동 청산", "MANUAL_EXIT_ORDER_CREATED", "MANUAL_EXIT_ORDER_NOT_SUBMITTED");

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
            long takeProfitPrice,
            long dailyUpperPrice,
            List<TakeProfitTranche> takeProfitTranches) {
        Position withCurrentPrice(long price) {
            return new Position(
                    stockCode,
                    stockName,
                    holdingQuantity,
                    sellableQuantity,
                    averagePrice,
                    price,
                    stopLossPrice,
                    takeProfitPrice,
                    dailyUpperPrice,
                    takeProfitTranches);
        }
    }

    private record CachedDailyPriceLimit(
            LocalDate tradingDate, long upperPrice, long retryAfterEpochMs) {}

    /** 분할 익절 한 단계(1차 또는 2차)의 목표가·목표수량·근거 퍼센트. */
    private record TakeProfitTranche(int tier, long price, int quantity, double percent) {}

    public record SettingsApplyResult(
            boolean balanceRefreshed, boolean completed, String message) {}

    public record PauseResult(int cancellationRequested, int cancellationFailed) {}

    /** 수동 청산 한 종목의 진행 상태를 모으는 가변 버퍼다. 응답으로는 {@link LiquidationItem}만 나간다. */
    private static final class Liquidation {
        private KiwoomTradeService.Holding holding;
        private int canceledOrders;
        private int submittedQuantity;
        private String brokerOrderNo;
        private String status = "WAITING_SELLABLE";
        private String message = "익절 주문 취소 확인 후 매도가능수량 복구를 기다리는 중입니다.";

        private Liquidation(KiwoomTradeService.Holding holding) {
            this.holding = holding;
        }

        private String stockCode() {
            return holding.code();
        }

        private boolean settled() {
            return !"WAITING_SELLABLE".equals(status);
        }

        private void markSubmitted(int quantity, String orderNo) {
            submittedQuantity = quantity;
            brokerOrderNo = orderNo;
            status = "SUBMITTED";
            message = quantity + "주 시장가 매도를 전송했습니다.";
        }

        private void markAlreadyFlat() {
            status = "SUBMITTED";
            message = "이미 보유 수량이 없습니다.";
        }

        private void markFailed(String reason) {
            status = "FAILED";
            message = reason == null ? "주문 전송에 실패했습니다." : reason;
        }

        private LiquidationItem toItem() {
            return new LiquidationItem(
                    holding.code(),
                    holding.name(),
                    holding.quantity(),
                    submittedQuantity,
                    canceledOrders,
                    brokerOrderNo == null ? "" : brokerOrderNo,
                    status,
                    message);
        }
    }

    public record LiquidationItem(
            String stockCode,
            String stockName,
            int quantity,
            int submittedQuantity,
            int canceledOrders,
            String brokerOrderNo,
            String status,
            String message) {}

    public record LiquidationResult(boolean accepted, String message, List<LiquidationItem> items) {
        static LiquidationResult blocked(String message) {
            return new LiquidationResult(false, message, List.of());
        }
    }
}
