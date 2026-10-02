package com.hyunchang.webapp.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.entity.*;
import com.hyunchang.webapp.repository.*;
import com.hyunchang.webapp.service.kiwoom.KiwoomUsAutoTradeState;
import com.hyunchang.webapp.util.KiwoomUsMarketHours;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

class KiwoomUsTrendExitTest {
    private final KiwoomProperties properties = new KiwoomProperties();
    private final KiwoomUsAutoTradeState state = mock(KiwoomUsAutoTradeState.class);
    private final KiwoomUsTechnicalSignalService signals =
            mock(KiwoomUsTechnicalSignalService.class);
    private final KiwoomUsTradeService trade = mock(KiwoomUsTradeService.class);
    private final KiwoomUsAccountHoldingRepository holdings =
            mock(KiwoomUsAccountHoldingRepository.class);
    private final KiwoomUsTradeProposalRepository proposals =
            mock(KiwoomUsTradeProposalRepository.class);
    private final KiwoomUsStrategySettingsService settings =
            mock(KiwoomUsStrategySettingsService.class);
    private final KiwoomUsAutoTradeService service =
            new KiwoomUsAutoTradeService(
                    properties,
                    trade,
                    settings,
                    mock(KiwoomUsFundamentalService.class),
                    mock(KiwoomUsIndexUniverseService.class),
                    signals,
                    state,
                    holdings,
                    proposals,
                    mock(KiwoomUsStrategyRunRepository.class),
                    mock(KiwoomUsAuditService.class),
                    mock(KiwoomUsEventService.class));

    private KiwoomUsTradeProposal buy() {
        var buy = new KiwoomUsTradeProposal();
        ReflectionTestUtils.setField(buy, "id", 42L);
        buy.setTrendExitPlan(new KiwoomUsTrendExitPlan(2, 4, 2, 1, 5));
        buy.setQuantity(3);
        buy.syncFill(3, 0, new BigDecimal("100"));
        return buy;
    }

    private KiwoomUsAccountHolding position(double price) {
        var h = new KiwoomUsAccountHolding();
        h.setSymbol("TEST");
        h.setExchange("ND");
        h.sync("mixed", 10, 10, new BigDecimal("80"), BigDecimal.valueOf(price), 20);
        h.reconcileManagedQuantity(3, 0);
        h.setManagedAveragePrice(new BigDecimal("100"));
        h.restoreTrendExitPlan(buy());
        when(holdings.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(h));
        // A later change to legacy mode/thresholds must not change a stored trend exit plan.
        var current = new KiwoomUsStrategySettings();
        current.setSignalMode(KiwoomUsStrategySettings.SignalMode.LEGACY);
        current.setTakeProfitPercent(1);
        current.setTakeProfitPercent2(2);
        when(settings.current()).thenReturn(current);
        when(proposals.save(any())).thenAnswer(i -> i.getArgument(0));
        return h;
    }

    @Test
    void entryRechecksSignalSizesByRiskAndRestoresPlanAfterPartialFill() throws Exception {
        properties.setAppKey("test");
        properties.setSecretKey("test");
        properties.setAccountNo("test");
        properties.getUs().setStrategyEnabled(true);
        when(state.isAutoTrading()).thenReturn(true);
        var configured = new KiwoomUsStrategySettings();
        configured.setSignalMode(KiwoomUsStrategySettings.SignalMode.TREND);
        configured.setMaxOrderPercent(50);
        var snapshot = mock(KiwoomUsAutoTradeService.AccountSnapshot.class);
        when(snapshot.automatedCapitalUsd()).thenReturn(new BigDecimal("10000"));
        var spy = spy(service);
        doReturn(snapshot).when(spy).refreshAccountSnapshot();
        doReturn(
                        new KiwoomUsTradeService.UsdCash(
                                new BigDecimal("10000"), "USD", BigDecimal.ZERO, true, ""))
                .when(spy)
                .refreshUsdCash();
        var quote =
                new KiwoomUsTradeService.OrderBookQuote(
                        new BigDecimal("100"), new BigDecimal("100"));
        when(trade.getOrderBook("ND", "TEST")).thenReturn(Mono.just(quote));
        var signal =
                new KiwoomUsTechnicalSignalService.Signal(
                        true,
                        true,
                        "confirmed",
                        5.0,
                        10.0,
                        3.0,
                        5.0,
                        1.0,
                        100.0,
                        2.0,
                        LocalDate.of(2026, 10, 1));
        when(signals.evaluate(anyString(), anyString(), any(), any())).thenReturn(signal);
        when(proposals.save(any()))
                .thenAnswer(
                        i -> {
                            var p = (KiwoomUsTradeProposal) i.getArgument(0);
                            ReflectionTestUtils.setField(p, "id", 101L);
                            return p;
                        });
        when(trade.placeOrder(any()))
                .thenReturn(Mono.just(new ObjectMapper().readTree("{\"ord_no\":\"buy1\"}")));
        var candidate =
                new KiwoomUsAutoTradeService.Candidate(
                        1,
                        "ND",
                        "TEST",
                        "Test",
                        new BigDecimal("100"),
                        3,
                        2,
                        null,
                        null,
                        0,
                        50,
                        new BigDecimal("1000000"),
                        "S&P 500",
                        signal);
        KiwoomUsTradeProposal placed;
        try (var hours = mockStatic(KiwoomUsMarketHours.class, CALLS_REAL_METHODS)) {
            hours.when(KiwoomUsMarketHours::isEntryWindow).thenReturn(true);
            placed = ReflectionTestUtils.invokeMethod(spy, "submitBuy", candidate, configured);
        }
        assertNotNull(placed);
        assertEquals(25, placed.getQuantity()); // $50 risk / $2 per share, below the $5000 cap
        assertEquals(2.0, placed.getTrendExitPlan().getTrendRiskPerShare());
        verify(signals).evaluate("ND", "TEST", quote, configured);
        placed.syncFill(10, 15, new BigDecimal("100"));
        var h = new KiwoomUsAccountHolding();
        h.setSymbol("TEST");
        h.setExchange("ND");
        h.sync("mixed", 15, 15, new BigDecimal("80"), new BigDecimal("100"), 25);
        when(proposals.findByExchangeAndSymbolOrderByIdAsc("ND", "TEST"))
                .thenReturn(List.of(placed));
        ReflectionTestUtils.invokeMethod(service, "reconcileHoldingOwnership", h);
        assertEquals(10, h.getManagedQuantity());
        assertEquals(1.0, h.getTrendExitPlan().getTrendEntryAtr());
        assertNotNull(h.getTrendStartedOn());
        h.updateTrendStop();
        assertEquals(new BigDecimal("98.0000"), h.getTrendStopPrice());
        ReflectionTestUtils.invokeMethod(service, "reconcileHoldingOwnership", h);
        assertEquals(10, h.getManagedQuantity());
        assertEquals(new BigDecimal("98.0000"), h.getTrendStopPrice());
    }

    @Test
    void trailingStopWaitsForActivationAndNeverMovesDownAcrossReplay() {
        var h = position(103);
        h.updateTrendStop();
        assertEquals(new BigDecimal("96.0000"), h.getTrendStopPrice());
        h.sync("mixed", 10, 10, new BigDecimal("80"), new BigDecimal("110"), 30);
        h.updateTrendStop();
        assertEquals(new BigDecimal("106.0000"), h.getTrendStopPrice());
        h.restoreTrendExitPlan(buy()); // restart/reconciliation of the same entry
        h.sync("mixed", 10, 10, new BigDecimal("80"), new BigDecimal("107"), 25);
        h.updateTrendStop();
        assertEquals(new BigDecimal("106.0000"), h.getTrendStopPrice());
        assertEquals(new BigDecimal("110"), h.getTrendHighWaterPrice());
    }

    @Test
    void fallingThroughTrailSellsOnlyManagedQuantityEvenAfterModeSwitch() throws Exception {
        var h = position(110);
        h.updateTrendStop();
        h.sync("mixed", 10, 10, new BigDecimal("80"), new BigDecimal("105"), 25);
        when(trade.placeOrder(any()))
                .thenReturn(Mono.just(new ObjectMapper().readTree("{\"ord_no\":\"sell1\"}")));
        ReflectionTestUtils.invokeMethod(service, "evaluateExits");
        var order = ArgumentCaptor.forClass(KiwoomUsTradeService.Order.class);
        verify(trade).placeOrder(order.capture());
        assertEquals(3, order.getValue().quantity());
        assertEquals("SELL", order.getValue().side());
        assertTrue(order.getValue().market());
    }

    @Test
    void profitAboveOldFixedTargetDoesNotLiquidateTrendPosition() {
        position(110);
        ReflectionTestUtils.invokeMethod(service, "evaluateExits");
        verify(trade, never()).placeOrder(any());
    }

    @Test
    void missingHoldingDateDoesNotBlockPriceStop() throws Exception {
        var h = position(95);
        ReflectionTestUtils.setField(h, "trendStartedOn", null);
        when(trade.placeOrder(any()))
                .thenReturn(Mono.just(new ObjectMapper().readTree("{\"ord_no\":\"sell3\"}")));
        ReflectionTestUtils.invokeMethod(service, "evaluateExits");
        verify(trade).placeOrder(argThat(o -> o.quantity() == 3 && o.market()));
    }

    @Test
    void unknownSellAndUncertainCostNeverProduceAnotherOrder() {
        var h = position(95);
        var sell = new KiwoomUsTradeProposal();
        sell.setAction(KiwoomUsTradeProposal.Action.SELL);
        sell.setSymbol("TEST");
        sell.unknown("timeout");
        when(proposals.findByStatusIn(any())).thenReturn(List.of(sell));
        ReflectionTestUtils.invokeMethod(service, "evaluateExits");
        verify(trade, never()).placeOrder(any());
        verify(trade, never()).cancelOrder(anyString(), anyString(), anyString(), anyInt());
        when(proposals.findByStatusIn(any())).thenReturn(List.of());
        h.setManagedAveragePrice(null);
        ReflectionTestUtils.invokeMethod(service, "evaluateExits");
        verify(trade, never()).placeOrder(any());
    }

    @Test
    void holidaysAndWeekendsDoNotCountTowardsTradingDayExit() {
        assertEquals(
                0,
                KiwoomUsMarketHours.elapsedTradingDays(
                        LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 7)));
        assertEquals(
                1,
                KiwoomUsMarketHours.elapsedTradingDays(
                        LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 8)));
        assertEquals(
                5,
                KiwoomUsMarketHours.elapsedTradingDays(
                        LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 14)));
    }

    @Test
    void durationExitUsesFrozenTradingDaysNotCurrentCalendarDaySetting() throws Exception {
        var h = position(101);
        ReflectionTestUtils.setField(h, "trendStartedOn", LocalDate.of(2026, 9, 4));
        when(trade.placeOrder(any()))
                .thenReturn(Mono.just(new ObjectMapper().readTree("{\"ord_no\":\"sell2\"}")));
        try (var hours = mockStatic(KiwoomUsMarketHours.class, CALLS_REAL_METHODS)) {
            hours.when(KiwoomUsMarketHours::today).thenReturn(LocalDate.of(2026, 9, 14));
            ReflectionTestUtils.invokeMethod(service, "evaluateExits");
        }
        verify(trade).placeOrder(argThat(o -> o.quantity() == 3 && o.market()));
    }

    @Test
    void firstFillTimeSurvivesPartialFillReplayAndPositionCloseResetsTrail() {
        var p = buy();
        LocalDateTime first = p.getFirstFilledAt();
        p.syncFill(3, 0, new BigDecimal("101"));
        assertEquals(first, p.getFirstFilledAt());
        var h = position(110);
        h.updateTrendStop();
        h.deactivate();
        assertNull(h.getTrendExitPlan());
        assertNull(h.getTrendStopPrice());
        assertNull(h.getTrendStartedOn());
    }
}
