package com.hyunchang.webapp.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.entity.*;
import com.hyunchang.webapp.repository.*;
import com.hyunchang.webapp.service.kiwoom.KiwoomUsAutoTradeState;
import com.hyunchang.webapp.util.KiwoomUsMarketHours;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

class KiwoomUsExecutionSafetyTest {
    private final ObjectMapper json = new ObjectMapper();
    private final KiwoomUsTradeService trade = mock(KiwoomUsTradeService.class);
    private final KiwoomUsAccountHoldingRepository holdings =
            mock(KiwoomUsAccountHoldingRepository.class);
    private final KiwoomUsTradeProposalRepository proposals =
            mock(KiwoomUsTradeProposalRepository.class);
    private final KiwoomUsStrategySettingsService settings =
            mock(KiwoomUsStrategySettingsService.class);
    private final KiwoomProperties properties = mock(KiwoomProperties.class);
    private final KiwoomUsAutoTradeService service =
            new KiwoomUsAutoTradeService(
                    properties,
                    trade,
                    settings,
                    mock(KiwoomUsFundamentalService.class),
                    mock(KiwoomUsIndexUniverseService.class),
                    mock(KiwoomUsTechnicalSignalService.class),
                    mock(KiwoomUsAutoTradeState.class),
                    holdings,
                    proposals,
                    mock(KiwoomUsStrategyRunRepository.class),
                    mock(KiwoomUsAuditService.class),
                    mock(KiwoomUsEventService.class));

    private KiwoomUsTradeProposal buy(int quantity) {
        var p = new KiwoomUsTradeProposal();
        p.setAction(KiwoomUsTradeProposal.Action.BUY);
        p.setExchange("ND");
        p.setSymbol("TEST");
        p.setQuantity(quantity);
        p.ordered("123", "test");
        return p;
    }

    private void brokerRows(KiwoomUsTradeProposal proposal, String records) throws Exception {
        when(proposals.findByStatusIn(any())).thenReturn(List.of(proposal));
        when(trade.getTodayFills())
                .thenReturn(Mono.just(json.readTree("{\"result_list\":" + records + "}")));
        when(trade.getOpenOrders()).thenReturn(Mono.just(json.readTree("{\"result_list\":[]}")));
    }

    @Test
    void canceledRemainderIsNeverInventedAsFilledShares() throws Exception {
        var p = buy(10);
        brokerRows(
                p,
                "[{\"ord_no\":\"123\",\"cntr_qty\":\"0\",\"ord_remnq\":\"0\",\"cncl_qty\":\"10\"}]");
        service.reconcileOrders();
        assertEquals(0, p.getFilledQuantity());
        assertEquals(KiwoomUsTradeProposal.Status.CANCELED, p.getStatus());
    }

    @Test
    void partialFillThenCancelKeepsActualOwnership() throws Exception {
        var p = buy(10);
        brokerRows(
                p,
                "[{\"ord_no\":\"123\",\"cntr_qty\":\"3\",\"ord_remnq\":\"0\",\"cncl_qty\":\"7\",\"cntr_uv\":\"10\"}]");
        service.reconcileOrders();
        assertEquals(3, p.getFilledQuantity());
        assertEquals(KiwoomUsTradeProposal.Status.PARTIALLY_FILLED_CANCELED, p.getStatus());
    }

    @Test
    void cancellationRequestAndDisappearanceAreNotConfirmation() throws Exception {
        var p = buy(10);
        p.requestCancel();
        brokerRows(
                p,
                "[{\"ord_no\":\"123\",\"cntr_qty\":\"0\",\"ord_remnq\":\"0\",\"cncl_qty\":\"10\",\"ord_stat\":\"취소요청\"}]");
        service.reconcileOrders();
        service.reconcileOrders();
        assertEquals(KiwoomUsTradeProposal.Status.CANCEL_REQUESTED, p.getStatus());
        assertEquals(10, p.getRemainingQuantity());
    }

    @Test
    void lateFullFillAfterCancelRequestWinsAndReplayIsIdempotent() throws Exception {
        var p = buy(10);
        p.requestCancel();
        brokerRows(p, "[{\"ord_no\":\"123\",\"cntr_qty\":\"10\",\"ord_remnq\":\"0\"}]");
        service.reconcileOrders();
        service.reconcileOrders();
        assertEquals(KiwoomUsTradeProposal.Status.FILLED, p.getStatus());
        assertEquals(10, p.getFilledQuantity());
    }

    private KiwoomUsAccountHolding arrangeHolding() throws Exception {
        var h = new KiwoomUsAccountHolding();
        h.setExchange("ND");
        h.setSymbol("TEST");
        h.sync("Test", 13, 13, BigDecimal.TEN, new BigDecimal("9"), -10);
        when(holdings.findByExchangeAndSymbol("ND", "TEST")).thenReturn(Optional.of(h));
        when(holdings.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(h));
        JsonNode balance = json.readTree("{}");
        when(trade.getBalance()).thenReturn(Mono.just(balance));
        when(trade.holdings(balance))
                .thenReturn(
                        List.of(
                                new KiwoomUsTradeService.Holding(
                                        "ND",
                                        "TEST",
                                        "Test",
                                        13,
                                        13,
                                        BigDecimal.TEN,
                                        new BigDecimal("9"),
                                        new BigDecimal("117"),
                                        new BigDecimal("-13"),
                                        -10)));
        when(settings.current()).thenReturn(new KiwoomUsStrategySettings());
        when(proposals.save(any())).thenAnswer(call -> call.getArgument(0));
        when(trade.placeOrder(any())).thenReturn(Mono.just(json.readTree("{\"ord_no\":\"456\"}")));
        return h;
    }

    @Test
    void delayedFillRecoversAutomaticExitAndOnlySellsAutomaticQuantity() throws Exception {
        var h = arrangeHolding();
        var p = buy(3);
        when(proposals.findByExchangeAndSymbolOrderByIdAsc("ND", "TEST")).thenReturn(List.of(p));
        service.syncHoldings();
        assertFalse(h.isManagedByAutoTrade());
        p.syncFill(3, 0, BigDecimal.TEN);
        service.syncHoldings();
        assertEquals(3, h.getManagedQuantity());
        ReflectionTestUtils.invokeMethod(service, "evaluateExits");
        ArgumentCaptor<KiwoomUsTradeService.Order> order =
                ArgumentCaptor.forClass(KiwoomUsTradeService.Order.class);
        verify(trade).placeOrder(order.capture());
        assertEquals(3, order.getValue().quantity());
        assertEquals("SELL", order.getValue().side());
    }

    @Test
    void orderQueryFailureDoesNotStopIndependentPositionExit() throws Exception {
        arrangeHolding();
        var p = buy(3);
        p.syncFill(3, 0, BigDecimal.TEN);
        when(proposals.findByExchangeAndSymbolOrderByIdAsc("ND", "TEST")).thenReturn(List.of(p));
        when(proposals.findByStatusIn(any())).thenReturn(List.of(buy(1)));
        when(trade.getTodayFills())
                .thenReturn(Mono.error(new IllegalStateException("read outage")));
        when(properties.isConfigured()).thenReturn(true);
        try (var hours = mockStatic(KiwoomUsMarketHours.class)) {
            hours.when(KiwoomUsMarketHours::isOpen).thenReturn(true);
            service.scheduledAccountAndExitSync();
        }
        verify(trade).placeOrder(any());
    }

    @Test
    void balanceQueryFailureDoesNotExitUsingStaleHoldings() throws Exception {
        var h = arrangeHolding();
        h.reconcileManagedQuantity(3, 0);
        when(properties.isConfigured()).thenReturn(true);
        when(trade.getBalance())
                .thenReturn(Mono.error(new IllegalStateException("balance outage")));
        try (var hours = mockStatic(KiwoomUsMarketHours.class)) {
            hours.when(KiwoomUsMarketHours::isOpen).thenReturn(true);
            service.scheduledAccountAndExitSync();
        }
        verify(trade, never()).placeOrder(any());
    }

    @Test
    void staleSnapshotDoesNotTriggerAnExit() throws Exception {
        var h = arrangeHolding();
        h.reconcileManagedQuantity(3, 0);
        ReflectionTestUtils.setField(h, "syncedAt", LocalDateTime.now().minusMinutes(2));
        ReflectionTestUtils.invokeMethod(service, "evaluateExits");
        verify(trade, never()).placeOrder(any());
    }

    @Test
    void unknownOrderBlocksRestart() {
        var p = buy(1);
        p.unknown("timeout");
        when(proposals.findByStatusIn(any())).thenReturn(List.of(p));
        assertThrows(IllegalStateException.class, service::validateRestart);
    }

    @Test
    void oldOrderNumberIsNotMatchedAgainstTodaysReusedNumber() throws Exception {
        var p = buy(10);
        var oldTime = LocalDateTime.now().minusDays(2);
        ReflectionTestUtils.setField(p, "orderedAt", oldTime);
        brokerRows(p, "[{\"ord_no\":\"123\",\"cntr_qty\":\"10\"}]");
        when(trade.getOrderHistory(any(), any()))
                .thenReturn(
                        Mono.just(
                                json.readTree(
                                        "{\"result_list\":[{\"ord_no\":\"123\",\"cntr_qty\":\"3\",\"cncl_qty\":\"7\",\"cntr_uv\":\"10\"}]}")));
        service.reconcileOrders();
        assertEquals(3, p.getFilledQuantity());
        assertEquals(KiwoomUsTradeProposal.Status.PARTIALLY_FILLED_CANCELED, p.getStatus());
    }

    @Test
    void manuallyClosedOldPositionDoesNotPolluteNewAutomaticCost() throws Exception {
        var h = arrangeHolding();
        var old = buy(3);
        old.syncFill(3, 0, new BigDecimal("100"));
        ReflectionTestUtils.setField(old, "orderedAt", LocalDateTime.now().minusDays(2));
        h.reconcileManagedQuantity(3, 0);
        h.sync("Test", 0, 0, BigDecimal.TEN, BigDecimal.TEN, 0);
        h.reconcileManagedQuantity(3, 0);
        h.deactivate();
        ReflectionTestUtils.setField(h, "lastClosedAt", LocalDateTime.now().minusDays(1));
        var fresh = buy(2);
        fresh.syncFill(2, 0, BigDecimal.TEN);
        when(proposals.findByExchangeAndSymbolOrderByIdAsc("ND", "TEST"))
                .thenReturn(List.of(old, fresh));
        service.syncHoldings();
        assertEquals(2, h.getManagedQuantity());
        assertEquals(0, h.getManagedAveragePrice().compareTo(BigDecimal.TEN));
    }
}
