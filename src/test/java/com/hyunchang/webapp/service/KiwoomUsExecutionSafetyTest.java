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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

class KiwoomUsExecutionSafetyTest {
    private static final LocalDate TRADING_DATE = LocalDate.of(2026, 11, 17);
    private static final LocalDateTime ORDERED_AT =
            TRADING_DATE
                    .atTime(12, 0)
                    .atZone(KiwoomUsMarketHours.ET)
                    .withZoneSameInstant(ZoneId.systemDefault())
                    .toLocalDateTime();

    private final ObjectMapper json = new ObjectMapper();
    private final KiwoomUsTradeService trade = mock(KiwoomUsTradeService.class);
    private final KiwoomUsAccountHoldingRepository holdings =
            mock(KiwoomUsAccountHoldingRepository.class);
    private final KiwoomUsTradeProposalRepository proposals =
            mock(KiwoomUsTradeProposalRepository.class);
    private final KiwoomUsStrategySettingsService settings =
            mock(KiwoomUsStrategySettingsService.class);
    private final KiwoomProperties properties = mock(KiwoomProperties.class);
    private final KiwoomUsAccountRiskService risk = mock(KiwoomUsAccountRiskService.class);
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
                    mock(KiwoomUsEventService.class),
                    risk);

    private KiwoomUsTradeProposal buy(int quantity) {
        var p = new KiwoomUsTradeProposal();
        p.setAction(KiwoomUsTradeProposal.Action.BUY);
        p.setExchange("ND");
        p.setSymbol("TEST");
        p.setQuantity(quantity);
        p.ordered("123", "test");
        return p;
    }

    private void reconcileOnFixedTradingDate() {
        // Reconciliation scenarios must not switch to the historical path after ET day rollover.
        try (var hours = mockStatic(KiwoomUsMarketHours.class, CALLS_REAL_METHODS)) {
            hours.when(KiwoomUsMarketHours::today).thenReturn(TRADING_DATE);
            service.reconcileOrders();
        }
    }

    private void brokerRows(KiwoomUsTradeProposal proposal, String records) throws Exception {
        ReflectionTestUtils.setField(proposal, "orderedAt", ORDERED_AT);
        when(proposals.findByStatusIn(any())).thenReturn(List.of(proposal));
        when(trade.getTodayFills())
                .thenReturn(Mono.just(json.readTree("{\"result_list\":" + records + "}")));
        when(trade.getOpenOrders()).thenReturn(Mono.just(json.readTree("{\"result_list\":[]}")));
    }

    @Test
    void summaryExplainsTheSameReservedCashAndCapitalUsedForSizing() throws Exception {
        var balance = json.readTree("{\"tot_evlt_amt\":\"1500\"}");
        when(trade.getDepositForDisplay()).thenReturn(Mono.just(balance));
        when(trade.getBalanceForDisplay()).thenReturn(Mono.just(balance));
        when(trade.usdCash(balance))
                .thenReturn(
                        new KiwoomUsTradeService.UsdCash(
                                new BigDecimal("1000"), "USD", BigDecimal.ZERO, true, ""));
        when(trade.totalEvaluation(balance)).thenReturn(new BigDecimal("1500"));
        when(trade.totalAccountEquity(balance, balance)).thenReturn(new BigDecimal("2500"));
        when(trade.getKrwOrderServiceStatusForDisplay())
                .thenReturn(
                        Mono.just(
                                new KiwoomUsTradeService.KrwOrderServiceStatus(
                                        "CANCELED", "해지됨", "")));
        var mixed = new KiwoomUsAccountHolding();
        mixed.setSymbol("MIXED");
        mixed.sync("mixed", 10, 10, new BigDecimal("100"), new BigDecimal("100"), 0);
        mixed.reconcileManagedQuantity(5, 0);
        var manual = new KiwoomUsAccountHolding();
        manual.setSymbol("MANUAL");
        manual.sync("manual", 5, 5, new BigDecimal("100"), new BigDecimal("100"), 0);
        when(holdings.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(mixed, manual));
        var pending = buy(2);
        pending.setLimitPrice(new BigDecimal("100"));
        when(proposals.findByStatusIn(any())).thenReturn(List.of(pending));
        var configured = new KiwoomUsStrategySettings();
        configured.setMaxOrderPercent(10);
        configured.setRiskPerTradePercent(0.5);
        when(settings.current()).thenReturn(configured);

        for (var mode : KiwoomUsStrategySettings.SignalMode.values()) {
            configured.setSignalMode(mode);
            var snapshot = service.accountSummary();
            assertEquals(0, new BigDecimal("500").compareTo(snapshot.managedEvaluationUsd()));
            assertEquals(0, new BigDecimal("1500").compareTo(snapshot.automatedCapitalUsd()));
            assertEquals(1, snapshot.managedPositionCount());
            assertEquals(2, snapshot.positionCount());
            assertEquals(0, new BigDecimal("200").compareTo(snapshot.buyingPower().reservedUsd()));
            assertEquals(
                    0, new BigDecimal("800").compareTo(snapshot.buyingPower().unreservedUsd()));
            assertEquals(1, snapshot.buyingPower().pendingPositionCount());
            assertNull(snapshot.buyingPower().holdingsSyncedAt());
            assertEquals(
                    0,
                    new BigDecimal(mode == KiwoomUsStrategySettings.SignalMode.TREND ? "150" : "80")
                            .compareTo(snapshot.perOrderLimitUsd()));
            if (mode == KiwoomUsStrategySettings.SignalMode.TREND)
                assertEquals(
                        0, new BigDecimal("7.5").compareTo(snapshot.buyingPower().riskBudgetUsd()));
            else assertNull(snapshot.buyingPower().riskBudgetUsd());
        }
        configured.setMaxOrderPercent(100);
        configured.setSignalMode(KiwoomUsStrategySettings.SignalMode.TREND);
        var capped = service.accountSummary();
        assertEquals(0, new BigDecimal("792").compareTo(capped.perOrderLimitUsd()));
        verify(holdings, never()).save(any());
        verify(risk, never()).observe(any());

        when(trade.getDepositForDisplay())
                .thenReturn(Mono.error(new IllegalStateException("572070: 수도결제중입니다")));
        var cached = service.accountSummary();
        assertFalse(cached.fresh());
        assertEquals(capped.buyingPower(), cached.buyingPower());
    }

    @Test
    void changingCashSnapshotDoesNotReachRiskObservation() throws Exception {
        when(trade.getDepositDetail())
                .thenReturn(
                        Mono.just(json.readTree("{\"cash\":1000}")),
                        Mono.just(json.readTree("{\"cash\":900}")));
        when(trade.getBalance()).thenReturn(Mono.just(json.readTree("{\"result_list\":[]}")));
        assertThrows(IllegalStateException.class, service::refreshAccountSnapshot);
        verify(risk, never()).observe(any());
        verify(holdings, never()).save(any());
    }

    @Test
    void displayDoesNotObserveRiskButScheduledSnapshotStillDoes() throws Exception {
        var data = json.readTree("{\"return_code\":0,\"result_list\":[]}");
        when(trade.getDepositForDisplay()).thenReturn(Mono.just(data));
        when(trade.getBalanceForDisplay()).thenReturn(Mono.just(data));
        when(trade.getDepositDetail()).thenReturn(Mono.just(data));
        when(trade.getBalance()).thenReturn(Mono.just(data));
        var status = new KiwoomUsTradeService.KrwOrderServiceStatus("CANCELED", "해지됨", "");
        when(trade.getKrwOrderServiceStatusForDisplay()).thenReturn(Mono.just(status));
        when(trade.getKrwOrderServiceStatus()).thenReturn(Mono.just(status));
        when(trade.usdCash(data))
                .thenReturn(
                        new KiwoomUsTradeService.UsdCash(
                                BigDecimal.TEN, "USD", BigDecimal.ZERO, true, ""));
        when(trade.totalEvaluation(data)).thenReturn(BigDecimal.ZERO);
        when(trade.totalAccountEquity(data, data)).thenReturn(BigDecimal.TEN);
        when(settings.current()).thenReturn(new KiwoomUsStrategySettings());
        service.accountSummary();
        verify(risk, never()).observe(any());
        verify(trade, never()).getDepositDetail();
        verify(trade, never()).getBalance();
        verify(holdings, never()).save(any());
        service.refreshAccountSnapshot();
        verify(risk).observe(BigDecimal.TEN);
    }

    @Test
    void missingBrokerOrderNumberBlocksOrderGuard() throws Exception {
        when(trade.getOpenOrders())
                .thenReturn(Mono.just(json.readTree("{\"result_list\":[{\"stk_cd\":\"TEST\"}]}")));
        assertThrows(
                IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "requireKnownBrokerOrders"));
        verify(trade, never()).placeOrder(any());
    }

    @Test
    void canceledRemainderIsNeverInventedAsFilledShares() throws Exception {
        var p = buy(10);
        brokerRows(
                p,
                "[{\"ord_no\":\"123\",\"cntr_qty\":\"0\",\"ord_remnq\":\"0\",\"cncl_qty\":\"10\"}]");
        reconcileOnFixedTradingDate();
        assertEquals(0, p.getFilledQuantity());
        assertEquals(KiwoomUsTradeProposal.Status.CANCELED, p.getStatus());
        verify(trade, never()).getOrderHistory(any(), any());
    }

    @Test
    void partialFillThenCancelKeepsActualOwnership() throws Exception {
        var p = buy(10);
        brokerRows(
                p,
                "[{\"ord_no\":\"123\",\"cntr_qty\":\"3\",\"ord_remnq\":\"0\",\"cncl_qty\":\"7\",\"cntr_uv\":\"10\"}]");
        reconcileOnFixedTradingDate();
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
        reconcileOnFixedTradingDate();
        reconcileOnFixedTradingDate();
        assertEquals(KiwoomUsTradeProposal.Status.CANCEL_REQUESTED, p.getStatus());
        assertEquals(10, p.getRemainingQuantity());
    }

    @Test
    void lateFullFillAfterCancelRequestWinsAndReplayIsIdempotent() throws Exception {
        var p = buy(10);
        p.requestCancel();
        brokerRows(p, "[{\"ord_no\":\"123\",\"cntr_qty\":\"10\",\"ord_remnq\":\"0\"}]");
        reconcileOnFixedTradingDate();
        reconcileOnFixedTradingDate();
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
        try (var hours = mockStatic(KiwoomUsMarketHours.class, CALLS_REAL_METHODS)) {
            hours.when(KiwoomUsMarketHours::isRegularSession).thenReturn(true);
            ReflectionTestUtils.invokeMethod(service, "evaluateExits");
        }
        ArgumentCaptor<KiwoomUsTradeService.Order> order =
                ArgumentCaptor.forClass(KiwoomUsTradeService.Order.class);
        verify(trade).placeOrder(order.capture());
        assertEquals(3, order.getValue().quantity());
        assertEquals("SELL", order.getValue().side());
        assertTrue(order.getValue().market());
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
            hours.when(KiwoomUsMarketHours::isRegularSession).thenReturn(true);
            service.scheduledAccountAndExitSync();
        }
        verify(trade).placeOrder(any());
    }

    @Test
    void latchedAccountLiquidationUsesFreshHoldingsWithoutDepositAvailability() throws Exception {
        arrangeHolding();
        when(risk.status())
                .thenReturn(
                        new com.hyunchang.webapp.dto.KiwoomUsRiskResponse(
                                true,
                                BigDecimal.valueOf(900),
                                BigDecimal.valueOf(1000),
                                BigDecimal.TEN,
                                10,
                                true,
                                true,
                                true,
                                java.time.OffsetDateTime.now(),
                                "triggered"));
        when(trade.getDepositDetail())
                .thenReturn(Mono.error(new IllegalStateException("settlement unavailable")));
        when(trade.getOpenOrders()).thenReturn(Mono.just(json.readTree("{\"result_list\":[]}")));
        when(trade.getOrderBook("ND", "TEST"))
                .thenReturn(
                        Mono.just(
                                new KiwoomUsTradeService.OrderBookQuote(
                                        new BigDecimal("9"), new BigDecimal("9.01"))));
        when(properties.isConfigured()).thenReturn(true);
        try (var hours = mockStatic(KiwoomUsMarketHours.class)) {
            hours.when(KiwoomUsMarketHours::isOpen).thenReturn(true);
            hours.when(KiwoomUsMarketHours::isRegularSession).thenReturn(true);
            service.scheduledAccountAndExitSync();
        }
        var order = ArgumentCaptor.forClass(KiwoomUsTradeService.Order.class);
        verify(trade).placeOrder(order.capture());
        assertEquals(13, order.getValue().quantity());
        assertFalse(order.getValue().market());
        verify(trade, never()).getDepositDetail();
    }

    @Test
    void wholeAccountFirstProfitUsesHalfOfManualHoldingAndKeepsPartialFillTarget()
            throws Exception {
        var h = arrangeHolding();
        h.sync("manual", 13, 13, BigDecimal.TEN, new BigDecimal("10.4"), 4);
        when(risk.status())
                .thenReturn(
                        new com.hyunchang.webapp.dto.KiwoomUsRiskResponse(
                                true,
                                BigDecimal.valueOf(1000),
                                BigDecimal.valueOf(1000),
                                BigDecimal.ZERO,
                                10,
                                false,
                                false,
                                true,
                                java.time.OffsetDateTime.now(),
                                "active"));
        when(trade.getOpenOrders()).thenReturn(Mono.just(json.readTree("{\"result_list\":[]}")));
        when(trade.getOrderBook("ND", "TEST"))
                .thenReturn(
                        Mono.just(
                                new KiwoomUsTradeService.OrderBookQuote(
                                        new BigDecimal("10.4"), new BigDecimal("10.41"))));
        var config = new KiwoomUsStrategySettings();
        config.setTakeProfitPercent(3);
        config.setTakeProfitPercent2(8);
        when(settings.current()).thenReturn(config);
        try (var hours = mockStatic(KiwoomUsMarketHours.class)) {
            hours.when(KiwoomUsMarketHours::isRegularSession).thenReturn(true);
            ReflectionTestUtils.invokeMethod(service, "evaluateExits");
        }
        var order = ArgumentCaptor.forClass(KiwoomUsTradeService.Order.class);
        verify(trade).placeOrder(order.capture());
        assertEquals(6, order.getValue().quantity());
        h.reconcileFirstTakeProfit(2, 6);
        assertEquals(4, h.remainingFirstTakeProfitQuantity(11));
        h.reconcileFirstTakeProfit(6, 6);
        assertTrue(h.isFirstTakeProfitCompleted());
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

    private void enableWholeAccountExits() throws Exception {
        when(risk.status())
                .thenReturn(
                        new com.hyunchang.webapp.dto.KiwoomUsRiskResponse(
                                true,
                                BigDecimal.valueOf(1000),
                                BigDecimal.valueOf(1000),
                                BigDecimal.ZERO,
                                10,
                                false,
                                false,
                                true,
                                java.time.OffsetDateTime.now(),
                                "active"));
        when(trade.getOpenOrders()).thenReturn(Mono.just(json.readTree("{\"result_list\":[]}")));
        when(trade.getOrderBook("ND", "TEST"))
                .thenReturn(
                        Mono.just(
                                new KiwoomUsTradeService.OrderBookQuote(
                                        new BigDecimal("9"), new BigDecimal("9.01"))));
    }

    private void evaluateExitsInRegularSession() {
        try (var hours = mockStatic(KiwoomUsMarketHours.class, CALLS_REAL_METHODS)) {
            hours.when(KiwoomUsMarketHours::isRegularSession).thenReturn(true);
            ReflectionTestUtils.invokeMethod(service, "evaluateExits");
        }
    }

    @Test
    void wholeAccountMixedHoldingExitsEvenWhenAutomaticCostIsUnknown() throws Exception {
        var h = arrangeHolding();
        h.reconcileManagedQuantity(3, 0);
        assertTrue(Double.isNaN(h.managedProfitLossPercent()));
        enableWholeAccountExits();
        evaluateExitsInRegularSession();
        var order = ArgumentCaptor.forClass(KiwoomUsTradeService.Order.class);
        verify(trade).placeOrder(order.capture());
        assertEquals("SELL", order.getValue().side());
        assertEquals(13, order.getValue().quantity());
        assertFalse(order.getValue().market());
    }

    @Test
    void wholeAccountMixedHoldingDoesNotSellOnAutomaticLotLossAlone() throws Exception {
        var h = arrangeHolding();
        h.sync("mixed", 13, 13, new BigDecimal("9"), new BigDecimal("9"), 0);
        h.reconcileManagedQuantity(3, 0);
        h.setManagedAveragePrice(BigDecimal.TEN);
        assertTrue(h.managedProfitLossPercent() < -9);
        enableWholeAccountExits();
        evaluateExitsInRegularSession();
        verify(trade, never()).placeOrder(any());
    }

    @Test
    void wholeAccountMixedTrendHoldingUsesWholePositionExitInsteadOfUnknownLotCost()
            throws Exception {
        var h = arrangeHolding();
        h.reconcileManagedQuantity(3, 0);
        var p = buy(3);
        p.setTrendExitPlan(new KiwoomUsTrendExitPlan(1, 2, 2, 1, 40));
        h.restoreTrendExitPlan(p);
        enableWholeAccountExits();
        evaluateExitsInRegularSession();
        var order = ArgumentCaptor.forClass(KiwoomUsTradeService.Order.class);
        verify(trade).placeOrder(order.capture());
        assertEquals(13, order.getValue().quantity());
        var saved = ArgumentCaptor.forClass(KiwoomUsTradeProposal.class);
        verify(proposals, atLeastOnce()).save(saved.capture());
        assertTrue(saved.getValue().getReason().contains("혼합 보유 전체 계좌 손익 기준"));
        assertNull(h.getTrendStopPrice());
    }

    @Test
    void staleSnapshotDoesNotTriggerAnExit() throws Exception {
        var h = arrangeHolding();
        h.reconcileManagedQuantity(3, 0);
        ReflectionTestUtils.setField(h, "syncedAt", LocalDateTime.now().minusMinutes(2));
        try (var hours = mockStatic(KiwoomUsMarketHours.class, CALLS_REAL_METHODS)) {
            hours.when(KiwoomUsMarketHours::isRegularSession).thenReturn(true);
            ReflectionTestUtils.invokeMethod(service, "evaluateExits");
        }
        verify(trade, never()).placeOrder(any());
    }

    @Test
    void mixedTrendFirstProfitPartialFillRemainsAccountedAfterBalanceReconciliation()
            throws Exception {
        var h = arrangeHolding();
        h.sync("mixed", 13, 13, BigDecimal.TEN, new BigDecimal("10.6"), 6);
        h.reconcileManagedQuantity(3, 0);
        var entry = buy(3);
        entry.syncFill(3, 0, BigDecimal.TEN);
        entry.setTrendExitPlan(new KiwoomUsTrendExitPlan(1, 2, 2, 1, 40));
        h.restoreTrendExitPlan(entry);
        enableWholeAccountExits();
        evaluateExitsInRegularSession();
        var saved = ArgumentCaptor.forClass(KiwoomUsTradeProposal.class);
        verify(proposals, atLeastOnce()).save(saved.capture());
        var exit = saved.getValue();
        assertEquals(6, exit.getQuantity());
        assertTrue(exit.getReason().startsWith("1차 익절"));
        assertTrue(exit.getReason().contains("혼합 보유 전체 계좌 손익 기준"));
        exit.syncFill(2, 4, new BigDecimal("10.6"));
        when(proposals.findByExchangeAndSymbolOrderByIdAsc("ND", "TEST"))
                .thenReturn(List.of(entry, exit));
        when(trade.holdings(any()))
                .thenReturn(
                        List.of(
                                new KiwoomUsTradeService.Holding(
                                        "ND",
                                        "TEST",
                                        "mixed",
                                        11,
                                        7,
                                        BigDecimal.TEN,
                                        new BigDecimal("10.6"),
                                        new BigDecimal("116.6"),
                                        new BigDecimal("6.6"),
                                        6)));

        service.syncHoldings();
        assertEquals(4, h.remainingFirstTakeProfitQuantity(11));
        assertFalse(h.isFirstTakeProfitCompleted());
        service.syncHoldings();
        assertEquals(4, h.remainingFirstTakeProfitQuantity(11));
        exit.syncFill(6, 0, new BigDecimal("10.6"));
        when(trade.holdings(any()))
                .thenReturn(
                        List.of(
                                new KiwoomUsTradeService.Holding(
                                        "ND",
                                        "TEST",
                                        "mixed",
                                        7,
                                        7,
                                        BigDecimal.TEN,
                                        new BigDecimal("10.6"),
                                        new BigDecimal("74.2"),
                                        new BigDecimal("4.2"),
                                        6)));
        service.syncHoldings();
        assertTrue(h.isFirstTakeProfitCompleted());
        assertEquals(0, h.remainingFirstTakeProfitQuantity(7));
    }

    @Test
    void unknownOrderBlocksRestart() {
        var p = buy(1);
        p.unknown("timeout");
        when(proposals.findByStatusIn(any())).thenReturn(List.of(p));
        assertThrows(IllegalStateException.class, service::validateRestart);
    }

    @Test
    void interruptedSubmissionBlocksRestartAndRemainsUnknownWithoutBrokerEvidence()
            throws Exception {
        var p = new KiwoomUsTradeProposal();
        p.setAction(KiwoomUsTradeProposal.Action.BUY);
        p.setExchange("ND");
        p.setSymbol("TEST");
        p.setQuantity(5);
        p.submitting();
        ReflectionTestUtils.setField(p, "orderedAt", ORDERED_AT);
        var sentAt = p.getOrderedAt();
        brokerRows(p, "[]");
        assertThrows(IllegalStateException.class, service::validateRestart);
        reconcileOnFixedTradingDate();
        assertEquals(KiwoomUsTradeProposal.Status.UNKNOWN, p.getStatus());
        assertEquals(sentAt, p.getOrderedAt());
        verify(trade, never()).placeOrder(any());
        verify(proposals).saveAndFlush(p);
    }

    @Test
    void oldOrderNumberIsNotMatchedAgainstTodaysReusedNumber() throws Exception {
        var p = buy(10);
        var oldTime = ORDERED_AT.minusDays(2);
        brokerRows(p, "[{\"ord_no\":\"123\",\"cntr_qty\":\"10\"}]");
        ReflectionTestUtils.setField(p, "orderedAt", oldTime);
        when(trade.getOrderHistory(TRADING_DATE.minusDays(2), TRADING_DATE))
                .thenReturn(
                        Mono.just(
                                json.readTree(
                                        "{\"result_list\":[{\"ord_no\":\"123\",\"cntr_qty\":\"3\",\"cncl_qty\":\"7\",\"cntr_uv\":\"10\"}]}")));
        reconcileOnFixedTradingDate();
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
