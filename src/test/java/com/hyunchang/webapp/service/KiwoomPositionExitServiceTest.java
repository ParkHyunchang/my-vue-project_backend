package com.hyunchang.webapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.entity.KiwoomStrategySettings;
import com.hyunchang.webapp.entity.KiwoomTradeProposal;
import com.hyunchang.webapp.repository.KiwoomStrategyRunRepository;
import com.hyunchang.webapp.repository.KiwoomTradeProposalRepository;
import com.hyunchang.webapp.service.kiwoom.KiwoomAutoTradeState;
import com.hyunchang.webapp.service.kiwoom.KiwoomWebsocketClient;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class KiwoomPositionExitServiceTest {
    private static final String CODE = "063440";

    @Mock private KiwoomProperties props;
    @Mock private KiwoomTradeService trade;
    @Mock private KiwoomAutoTradeState state;
    @Mock private KiwoomStrategySettingsService settings;
    @Mock private KiwoomTradeProposalRepository proposals;
    @Mock private KiwoomStrategyRunRepository runs;
    @Mock private KiwoomProposalOrderService orders;
    @Mock private KiwoomStrategyAuditService audit;
    @Mock private KiwoomWebsocketClient websocket;
    @Mock private KiwoomAccountHoldingSyncService accountHoldings;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicLong ids = new AtomicLong(100);
    private final List<KiwoomTradeProposal> openOrders = new ArrayList<>();
    private KiwoomPositionExitService service;
    private KiwoomStrategySettings current;
    private Consumer<KiwoomWebsocketClient.PriceTick> priceListener;

    @BeforeEach
    void setUp() throws Exception {
        KiwoomProperties.Strategy strategy = new KiwoomProperties.Strategy();
        strategy.setEnabled(true);
        lenient().when(props.getStrategy()).thenReturn(strategy);
        lenient().when(props.isConfigured()).thenReturn(true);
        lenient().when(state.isAutoTrading()).thenReturn(true);
        lenient().when(state.isEmergencyStopped()).thenReturn(false);

        current = new KiwoomStrategySettings();
        current.setAutoExecute(true);
        current.setRiskLoopEnabled(true);
        current.setSwingStopLossPercent(10);
        current.setSwingTakeProfitPercent(10);
        current.setSwingMaxHoldingDays(5);
        lenient().when(settings.current()).thenReturn(current);
        lenient()
                .when(trade.getDailyPriceLimit(anyString()))
                .thenReturn(
                        Mono.just(
                                new KiwoomTradeService.DailyPriceLimit(
                                        CODE, 1_000_000, 1, 1_000)));

        lenient()
                .when(proposals.findByStatusIn(any()))
                .thenAnswer(
                        invocation -> {
                            List<KiwoomTradeProposal.Status> statuses = invocation.getArgument(0);
                            return openOrders.stream()
                                    .filter(order -> statuses.contains(order.getStatus()))
                                    .toList();
                        });
        lenient()
                .when(proposals.save(any()))
                .thenAnswer(
                        invocation -> {
                            KiwoomTradeProposal proposal = invocation.getArgument(0);
                            if (proposal.getId() == null) setId(proposal, ids.incrementAndGet());
                            if (!openOrders.contains(proposal)) openOrders.add(proposal);
                            return proposal;
                        });
        lenient().when(runs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ArgumentCaptor<Consumer<KiwoomWebsocketClient.PriceTick>> listenerCaptor =
                ArgumentCaptor.forClass(Consumer.class);
        service =
                new KiwoomPositionExitService(
                        props,
                        trade,
                        state,
                        settings,
                        proposals,
                        runs,
                        orders,
                        audit,
                        websocket,
                        accountHoldings);
        service.listenForPriceTicks();
        verify(websocket).addPriceListener(listenerCaptor.capture());
        priceListener = listenerCaptor.getValue();
    }

    @Test
    void sellableZeroPositionRemainsWatchedAndCancelsTakeProfitAtStopLoss() throws Exception {
        KiwoomTradeProposal takeProfit = takeProfitOrder(6, 1235, "0067224");
        openOrders.add(takeProfit);
        stubHolding(6, 0, 1119, 1100);
        when(orders.cancel(eq(takeProfit.getId()), eq(6), anyString()))
                .thenReturn(new KiwoomProposalOrderService.Result(true, "취소 요청", takeProfit));

        service.refreshPositions("TEST", true);
        service.handlePriceTick(CODE, 1000);

        verify(websocket).connectAndSubscribe(List.of(CODE));
        verify(orders).cancel(eq(takeProfit.getId()), eq(6), anyString());
        verify(orders, never()).autoExecute(anyLong());
    }

    @Test
    void stopOrderWaitsForCancellationAndSellableQuantityRecovery() throws Exception {
        KiwoomTradeProposal takeProfit = takeProfitOrder(6, 1235, "0067224");
        openOrders.add(takeProfit);
        stubHolding(6, 0, 1119, 1100);
        when(orders.cancel(eq(takeProfit.getId()), eq(6), anyString()))
                .thenAnswer(
                        invocation -> {
                            takeProfit.cancelRequested("테스트 취소", "{}");
                            return new KiwoomProposalOrderService.Result(true, "취소 요청", takeProfit);
                        });

        service.refreshPositions("TEST", true);
        service.handlePriceTick(CODE, 1000);
        service.refreshPositions("CANCEL_REQUESTED", true);
        verify(orders, never()).autoExecute(anyLong());

        takeProfit.cancelled();
        stubHolding(6, 6, 1119, 1000);
        when(orders.autoExecute(anyLong()))
                .thenAnswer(
                        invocation -> {
                            KiwoomTradeProposal stopOrder =
                                    openOrders.stream()
                                            .filter(
                                                    order ->
                                                            order.getReason() != null
                                                                    && order.getReason()
                                                                            .startsWith(
                                                                                    "[EXIT:STOP_LOSS]"))
                                            .findFirst()
                                            .orElseThrow();
                            stopOrder.ordered("{}", "0071000");
                            return new KiwoomProposalOrderService.Result(true, "주문 전송", stopOrder);
                        });

        service.retryPendingStopTransitionNow();
        priceListener.accept(new KiwoomWebsocketClient.PriceTick(CODE, 990));
        service.retryPendingStopTransitionNow();

        ArgumentCaptor<KiwoomTradeProposal> proposalCaptor =
                ArgumentCaptor.forClass(KiwoomTradeProposal.class);
        verify(proposals, org.mockito.Mockito.atLeastOnce()).save(proposalCaptor.capture());
        KiwoomTradeProposal stopOrder =
                proposalCaptor.getAllValues().stream()
                        .filter(
                                order ->
                                        order.getReason() != null
                                                && order.getReason().startsWith("[EXIT:STOP_LOSS]"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(KiwoomTradeProposal.OrderType.MARKET, stopOrder.getOrderType());
        assertEquals(6, stopOrder.getQuantity());
        verify(orders, times(1)).autoExecute(stopOrder.getId());
    }

    @Test
    void stopTransitionDelayAlertsEvenWhileTakeProfitCancellationIsPending() throws Exception {
        KiwoomTradeProposal takeProfit = takeProfitOrder(6, 1235, "0067224");
        openOrders.add(takeProfit);
        stubHolding(6, 0, 1119, 1100);
        when(orders.cancel(eq(takeProfit.getId()), eq(6), anyString()))
                .thenAnswer(
                        invocation -> {
                            takeProfit.cancelRequested("테스트 취소", "{}");
                            return new KiwoomProposalOrderService.Result(
                                    true, "취소 요청", takeProfit);
                        });

        service.refreshPositions("TEST", true);
        service.handlePriceTick(CODE, 1000);
        Field field = KiwoomPositionExitService.class.getDeclaredField("exitTriggeredAt");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var triggeredAt = (java.util.Map<String, LocalDateTime>) field.get(service);
        triggeredAt.put(CODE, LocalDateTime.now().minusSeconds(61));

        service.retryPendingStopTransitionNow();
        service.retryPendingStopTransitionNow();

        verify(websocket).publishEvent(eq("error"), contains("청산 전환 지연"));
        verify(orders, never()).autoExecute(anyLong());
    }

    @Test
    void maximumHoldingPeriodUsesSameCancelThenMarketExitFlow() throws Exception {
        KiwoomTradeProposal takeProfit = takeProfitOrder(6, 1235, "0067224");
        openOrders.add(takeProfit);
        stubHolding(6, 0, 1119, 1100);
        // 자동매수 제안 이력이 없는 수동 보유종목도 기존 계좌 보유 테이블의 시작일로 계산한다.
        when(accountHoldings.positionOpenedAt(CODE))
                .thenReturn(Optional.of(LocalDateTime.now().minusDays(10)));
        when(orders.cancel(eq(takeProfit.getId()), eq(6), anyString()))
                .thenAnswer(
                        invocation -> {
                            takeProfit.cancelRequested("테스트 취소", "{}");
                            return new KiwoomProposalOrderService.Result(true, "취소 요청", takeProfit);
                        });

        service.refreshPositions("TEST", true);

        verify(orders).cancel(eq(takeProfit.getId()), eq(6), anyString());
        verify(orders, never()).autoExecute(anyLong());

        takeProfit.cancelled();
        stubHolding(6, 6, 1119, 1100);
        when(orders.autoExecute(anyLong()))
                .thenAnswer(
                        invocation -> {
                            KiwoomTradeProposal timeExit =
                                    openOrders.stream()
                                            .filter(
                                                    order ->
                                                            order.getReason() != null
                                                                    && order.getReason()
                                                                            .startsWith(
                                                                                    "[EXIT:TIME_EXIT]"))
                                            .findFirst()
                                            .orElseThrow();
                            timeExit.ordered("{}", "0072000");
                            return new KiwoomProposalOrderService.Result(true, "주문 전송", timeExit);
                        });

        service.retryPendingStopTransitionNow();

        KiwoomTradeProposal timeExit =
                openOrders.stream()
                        .filter(
                                order ->
                                        order.getReason() != null
                                                && order.getReason().startsWith("[EXIT:TIME_EXIT]"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(KiwoomTradeProposal.OrderType.MARKET, timeExit.getOrderType());
        assertEquals(6, timeExit.getQuantity());
        verify(orders, times(1)).autoExecute(timeExit.getId());
    }

    @Test
    void preMarketPreparationRestoresPositionsWithoutSendingOrders() {
        stubHolding(6, 6, 1119, 1100);

        boolean prepared =
                service.preparePositionsBeforeMarketOpen("08:50 정기 사전 복구", "PRE_MARKET_0850");

        assertEquals(true, prepared);
        verify(websocket).connectAndSubscribe(List.of(CODE));
        verify(orders, never()).autoExecute(anyLong());
        verify(orders, never()).cancel(anyLong(), anyInt(), anyString());
    }

    @Test
    void zeroPercentDisablesTakeProfitAndStopLossOrders() {
        current.setSwingStopLossPercent(0);
        current.setSwingTakeProfitPercent(0);
        stubHolding(6, 6, 1119, 500);

        service.refreshPositions("TEST", true);
        service.handlePriceTick(CODE, 400);

        verify(orders, never()).autoExecute(anyLong());
        verify(orders, never()).cancel(anyLong(), anyInt(), anyString());
    }

    @Test
    void changedTakeProfitCancelsExistingOrderBeforeRepricing() throws Exception {
        KiwoomTradeProposal takeProfit = takeProfitOrder(6, 1235, "0067224");
        openOrders.add(takeProfit);
        current.setSwingTakeProfitPercent(5);
        stubHolding(6, 0, 1119, 1100);
        when(orders.cancel(eq(takeProfit.getId()), eq(6), anyString()))
                .thenAnswer(
                        invocation -> {
                            takeProfit.cancelRequested("테스트 취소", "{}");
                            return new KiwoomProposalOrderService.Result(true, "취소 요청", takeProfit);
                        });

        service.refreshPositions("SETTINGS_CHANGED", true);

        verify(orders).cancel(eq(takeProfit.getId()), eq(6), anyString());
        verify(orders, never()).autoExecute(anyLong());
    }

    @Test
    void disablingTakeProfitCancelsExistingOrder() throws Exception {
        KiwoomTradeProposal takeProfit = takeProfitOrder(6, 1235, "0067224");
        openOrders.add(takeProfit);
        current.setSwingTakeProfitPercent(0);
        stubHolding(6, 0, 1119, 1100);
        when(orders.cancel(eq(takeProfit.getId()), eq(6), anyString()))
                .thenAnswer(
                        invocation -> {
                            takeProfit.cancelRequested("테스트 취소", "{}");
                            return new KiwoomProposalOrderService.Result(true, "취소 요청", takeProfit);
                        });

        service.refreshPositions("SETTINGS_CHANGED", true);

        verify(orders).cancel(eq(takeProfit.getId()), eq(6), anyString());
        verify(orders, never()).autoExecute(anyLong());
    }

    @Test
    void completeStopCancelsAllOpenAutomatedOrders() throws Exception {
        KiwoomTradeProposal takeProfit = takeProfitOrder(6, 1235, "0067224");
        KiwoomTradeProposal pendingBuy = new KiwoomTradeProposal();
        setId(pendingBuy, ids.incrementAndGet());
        pendingBuy.setAction(KiwoomTradeProposal.Action.BUY);
        pendingBuy.setStockCode("005930");
        pendingBuy.setStockName("삼성전자");
        pendingBuy.setQuantity(2);
        pendingBuy.setReason("AI 자동 매수");
        pendingBuy.ordered("{}", "0067225");
        openOrders.add(takeProfit);
        openOrders.add(pendingBuy);
        when(orders.cancel(eq(takeProfit.getId()), eq(6), anyString()))
                .thenReturn(new KiwoomProposalOrderService.Result(true, "취소 요청", takeProfit));
        when(orders.cancel(eq(pendingBuy.getId()), eq(2), anyString()))
                .thenReturn(new KiwoomProposalOrderService.Result(true, "취소 요청", pendingBuy));

        KiwoomPositionExitService.PauseResult result = service.pauseExitManagement();

        assertEquals(2, result.cancellationRequested());
        assertEquals(0, result.cancellationFailed());
        verify(orders).cancel(eq(takeProfit.getId()), eq(6), anyString());
        verify(orders).cancel(eq(pendingBuy.getId()), eq(2), anyString());
    }

    @Test
    void singleShareHoldingUsesOneTierOneOrderEvenWithTier2Configured() {
        current.setSwingTakeProfitPercent2(20);
        current.setSwingTakeProfitSplitPercent(50);
        stubHolding(1, 1, 1000, 1000);
        stubAutoExecuteSuccess();

        service.refreshPositions("TEST", true);

        assertEquals(1, openOrders.size());
        KiwoomTradeProposal order = openOrders.get(0);
        assertEquals(1, order.getQuantity());
        assertEquals(1100L, order.getLimitPrice());
        verify(orders, times(1)).autoExecute(anyLong());
    }

    @Test
    void tierTwoDisabledFallsBackToSingleOrderForFullQuantity() {
        stubHolding(6, 6, 1000, 1000);
        stubAutoExecuteSuccess();

        service.refreshPositions("TEST", true);

        assertEquals(1, openOrders.size());
        assertEquals(6, openOrders.get(0).getQuantity());
        verify(orders, times(1)).autoExecute(anyLong());
    }

    @Test
    void sevenShareHoldingSplitsIntoTwoTiersWithFiftyFiftyRounding() {
        current.setSwingTakeProfitPercent2(20);
        current.setSwingTakeProfitSplitPercent(50);
        stubHolding(7, 7, 1000, 1000);
        stubAutoExecuteSuccess();

        service.refreshPositions("TEST", true);

        assertEquals(1, openOrders.size());
        KiwoomTradeProposal tier1 = openOrders.get(0);
        assertEquals(4, tier1.getQuantity());
        assertEquals(1100L, tier1.getLimitPrice());
        verify(orders, times(1)).autoExecute(anyLong());

        // 1차 주문이 매도가능수량을 4주만큼 줄였다고 가정 — 2차는 다음 주기에야 생성된다.
        stubHolding(7, 3, 1000, 1000);

        service.refreshPositions("TEST", true);

        assertEquals(2, openOrders.size());
        KiwoomTradeProposal tier2 =
                openOrders.stream()
                        .filter(o -> o.getReason().startsWith("[EXIT:TAKE_PROFIT-2]"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(3, tier2.getQuantity());
        assertEquals(1200L, tier2.getLimitPrice());
        verify(orders, times(2)).autoExecute(anyLong());
    }

    @Test
    void tierAboveDailyUpperLimitIsDeferredWithoutLoweringConfiguredTarget() {
        current.setSwingTakeProfitPercent2(20);
        current.setSwingTakeProfitSplitPercent(50);
        stubHolding(4, 4, 6_880, 7_500);
        when(trade.getDailyPriceLimit(CODE))
                .thenReturn(
                        Mono.just(
                                new KiwoomTradeService.DailyPriceLimit(
                                        CODE, 8_000, 4_320, 6_160)));
        stubAutoExecuteSuccess();

        service.refreshPositions("TEST", true);
        stubHolding(4, 2, 6_880, 7_500);
        service.refreshPositions("ORDER_STATE_CHANGED", true);

        assertEquals(1, openOrders.size());
        assertEquals(7_570L, openOrders.get(0).getLimitPrice());
        verify(orders, times(1)).autoExecute(anyLong());
    }

    @Test
    void tierMismatchCancelsOnlyThatTierAndRecreatesNextCycle() throws Exception {
        current.setSwingTakeProfitPercent2(20);
        current.setSwingTakeProfitSplitPercent(50);
        KiwoomTradeProposal staleTier1 = takeProfitTierOrder(1, 6, 1050, "0093000");
        openOrders.add(staleTier1);
        stubHolding(7, 0, 1000, 1000);
        when(orders.cancel(eq(staleTier1.getId()), eq(6), anyString()))
                .thenReturn(new KiwoomProposalOrderService.Result(true, "취소 요청", staleTier1));

        service.refreshPositions("TEST", true);

        verify(orders).cancel(eq(staleTier1.getId()), eq(6), anyString());
        verify(orders, never()).autoExecute(anyLong());

        staleTier1.cancelled();
        stubHolding(7, 7, 1000, 1000);
        stubAutoExecuteSuccess();

        service.refreshPositions("TEST", true);

        KiwoomTradeProposal recreated =
                openOrders.stream()
                        .filter(
                                o ->
                                        o.getStatus() != KiwoomTradeProposal.Status.CANCELED
                                                && o.getReason() != null
                                                && o.getReason().startsWith("[EXIT:TAKE_PROFIT-1]"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(4, recreated.getQuantity());
        assertEquals(1100L, recreated.getLimitPrice());
        verify(orders, times(1)).autoExecute(anyLong());
    }

    @Test
    void tierTwoPriceNotAboveTierOneFallsBackToSingleTranche() {
        current.setSwingTakeProfitPercent(9.9);
        current.setSwingTakeProfitPercent2(10.0);
        current.setSwingTakeProfitSplitPercent(50);
        stubHolding(6, 6, 1000, 1000);
        stubAutoExecuteSuccess();

        service.refreshPositions("TEST", true);

        assertEquals(1, openOrders.size());
        assertEquals(6, openOrders.get(0).getQuantity());
        assertEquals(1100L, openOrders.get(0).getLimitPrice());
        verify(orders, times(1)).autoExecute(anyLong());
    }

    /**
     * autoExecute가 불릴 때마다 그 시점에 아직 브로커에 전송 전인(PROPOSED) 주문을 찾아 체결 이전
     * 상태로 표시한다. 한 번의 ensureTakeProfitOrder 호출은 tranche 하나만 만들므로 항상 PROPOSED
     * 주문이 최대 1건이라 모호함이 없다. 같은 mock 메서드를 여러 번 stub하면 Mockito가 새 stub을
     * 등록하면서 기존 stub의 answer를 한 번 더 실행시키는 부작용이 있어(재현: 이 헬퍼를 tier별로
     * 다시 호출했다가 재현됨), 테스트당 한 번만 stub하고 여러 refreshPositions 호출에 재사용한다.
     */
    private void stubAutoExecuteSuccess() {
        when(orders.autoExecute(anyLong()))
                .thenAnswer(
                        invocation -> {
                            KiwoomTradeProposal order =
                                    openOrders.stream()
                                            .filter(
                                                    o ->
                                                            o.getStatus()
                                                                    == KiwoomTradeProposal.Status
                                                                            .PROPOSED)
                                            .findFirst()
                                            .orElseThrow();
                            order.ordered("{}", "0" + order.getId());
                            return new KiwoomProposalOrderService.Result(true, "주문 전송", order);
                        });
    }

    private void stubHolding(int quantity, int sellable, long averagePrice, long currentPrice) {
        JsonNode balance = objectMapper.createObjectNode();
        when(trade.getBalance()).thenReturn(Mono.just(balance));
        when(trade.parseHoldings(balance))
                .thenReturn(
                        List.of(
                                new KiwoomTradeService.Holding(
                                        CODE,
                                        "SM Life Design",
                                        quantity,
                                        sellable,
                                        averagePrice,
                                        currentPrice,
                                        0)));
    }

    private KiwoomTradeProposal takeProfitOrder(int quantity, long price, String orderNo)
            throws Exception {
        KiwoomTradeProposal proposal = new KiwoomTradeProposal();
        setId(proposal, ids.incrementAndGet());
        proposal.setAction(KiwoomTradeProposal.Action.SELL);
        proposal.setStockCode(CODE);
        proposal.setStockName("SM Life Design");
        proposal.setQuantity(quantity);
        proposal.setOrderType(KiwoomTradeProposal.OrderType.LIMIT);
        proposal.setLimitPrice(price);
        proposal.setReason("[EXIT:TAKE_PROFIT] 익절 지정가 주문");
        proposal.ordered("{}", orderNo);
        return proposal;
    }

    private KiwoomTradeProposal takeProfitTierOrder(int tier, int quantity, long price, String orderNo)
            throws Exception {
        KiwoomTradeProposal proposal = new KiwoomTradeProposal();
        setId(proposal, ids.incrementAndGet());
        proposal.setAction(KiwoomTradeProposal.Action.SELL);
        proposal.setStockCode(CODE);
        proposal.setStockName("SM Life Design");
        proposal.setQuantity(quantity);
        proposal.setOrderType(KiwoomTradeProposal.OrderType.LIMIT);
        proposal.setLimitPrice(price);
        proposal.setReason("[EXIT:TAKE_PROFIT-" + tier + "] " + tier + "차 익절 지정가 주문");
        proposal.ordered("{}", orderNo);
        return proposal;
    }

    private void setId(KiwoomTradeProposal proposal, long id) throws Exception {
        Field field = KiwoomTradeProposal.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(proposal, id);
    }

}
