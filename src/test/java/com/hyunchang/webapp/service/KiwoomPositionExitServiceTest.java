package com.hyunchang.webapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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
import java.util.ArrayList;
import java.util.List;
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
        when(props.getStrategy()).thenReturn(strategy);
        when(props.isConfigured()).thenReturn(true);
        when(state.isAutoTrading()).thenReturn(true);
        when(state.isEmergencyStopped()).thenReturn(false);

        current = new KiwoomStrategySettings();
        current.setAutoExecute(true);
        current.setRiskLoopEnabled(true);
        current.setSwingStopLossPercent(10);
        current.setSwingTakeProfitPercent(10);
        when(settings.current()).thenReturn(current);

        when(proposals.existsByStockCodeAndActionAndStatusIn(any(), any(), any())).thenReturn(true);
        when(proposals.findByStatusIn(any()))
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
        when(orders.cancel(takeProfit.getId(), 6))
                .thenReturn(new KiwoomProposalOrderService.Result(true, "취소 요청", takeProfit));

        service.refreshPositions("TEST");
        priceListener.accept(new KiwoomWebsocketClient.PriceTick(CODE, 1000));

        verify(websocket).connectAndSubscribe(List.of(CODE));
        verify(orders).cancel(takeProfit.getId(), 6);
        verify(orders, never()).autoExecute(anyLong());
    }

    @Test
    void stopOrderWaitsForCancellationAndSellableQuantityRecovery() throws Exception {
        KiwoomTradeProposal takeProfit = takeProfitOrder(6, 1235, "0067224");
        openOrders.add(takeProfit);
        stubHolding(6, 0, 1119, 1100);
        when(orders.cancel(takeProfit.getId(), 6))
                .thenAnswer(
                        invocation -> {
                            takeProfit.cancelRequested("{}");
                            return new KiwoomProposalOrderService.Result(true, "취소 요청", takeProfit);
                        });

        service.refreshPositions("TEST");
        priceListener.accept(new KiwoomWebsocketClient.PriceTick(CODE, 1000));
        service.refreshPositions("CANCEL_REQUESTED");
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

        service.retryPendingStopTransition();

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
        verify(orders).autoExecute(stopOrder.getId());
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

    private void setId(KiwoomTradeProposal proposal, long id) throws Exception {
        Field field = KiwoomTradeProposal.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(proposal, id);
    }
}
