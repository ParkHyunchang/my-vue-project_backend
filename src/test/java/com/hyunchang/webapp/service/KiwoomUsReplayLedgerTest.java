package com.hyunchang.webapp.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class KiwoomUsReplayLedgerTest {
    private static final Instant START = Instant.parse("2026-11-17T17:00:00Z");

    private static ObjectNode initial(String cash) {
        ObjectNode node =
                KiwoomUsProductionReplayHarness.JSON
                        .createObjectNode()
                        .put("cash_usd", new BigDecimal(cash));
        node.putArray("holdings");
        return node;
    }

    private static ObjectNode model(int bps) {
        return KiwoomUsProductionReplayHarness.JSON
                .createObjectNode()
                .put("fee_bps", bps)
                .put("settlement", "NEXT_TRADING_DAY");
    }

    private static ObjectNode frame(String id, Instant time, String bid, String ask, int size) {
        ObjectNode f =
                KiwoomUsProductionReplayHarness.JSON
                        .createObjectNode()
                        .put("id", id)
                        .put("at", time.toString());
        f.putObject("quotes")
                .putObject("TEST")
                .put("bid", new BigDecimal(bid))
                .put("ask", new BigDecimal(ask))
                .put("bid_size", size)
                .put("ask_size", size)
                .put("available_at", time.toString());
        return f;
    }

    private static KiwoomUsReplayLedger cashLedger(int fee) {
        KiwoomUsReplayLedger ledger = new KiwoomUsReplayLedger(initial("1000"), model(fee));
        ledger.advanceFrame(frame("initial", START, "99", "100", 100));
        return ledger;
    }

    private static void amount(String expected, ObjectNode report, String field) {
        assertEquals(
                0, new BigDecimal(expected).compareTo(report.path(field).decimalValue()), field);
    }

    @Test
    void acceptedOrderReservesThenLaterQuoteFillsAndChargesFees() {
        var l = cashLedger(10);
        assertEquals(
                0,
                l.submit("BUY", "ND", "TEST", 5, new BigDecimal("100"), false)
                        .path("return_code")
                        .asInt());
        amount("1000", l.report(), "cash_usd");
        amount("500.5", l.report(), "reserved_cash_usd");
        assertTrue(l.report().path("holdings").isEmpty());
        assertTrue(l.report().path("fills").isEmpty());
        l.advanceFrame(frame("later", START.plusSeconds(30), "99", "100", 100));
        amount("499.5", l.report(), "cash_usd");
        amount("0", l.report(), "reserved_cash_usd");
        amount("994.5", l.report(), "equity_usd");
        assertEquals(5, l.balance().path("result_list").get(0).path("poss_qty").asInt());
        assertEquals(1, l.report().path("fills").size());
    }

    @Test
    void partialFillConsumesCapacityOnlyOnceAndCancellationReleasesRemainingReserve() {
        var l = cashLedger(10);
        String no =
                l.submit("BUY", "ND", "TEST", 5, new BigDecimal("100"), false)
                        .path("ord_no")
                        .asText();
        l.advanceFrame(frame("partial", START.plusSeconds(30), "99", "100", 2));
        amount("799.8", l.report(), "cash_usd");
        amount("300.3", l.report(), "reserved_cash_usd");
        var repeated = frame("repeat", START.plusSeconds(60), "99", "100", 2);
        repeated.withObject("/quotes/TEST").put("available_at", START.plusSeconds(30).toString());
        l.advanceFrame(repeated);
        assertEquals(2, l.fills().path("result_list").get(0).path("cntr_qty").asInt());
        assertEquals(0, l.cancel("ND", "TEST", no, 3).path("return_code").asInt());
        amount("0", l.report(), "reserved_cash_usd");
        assertEquals("CANCELED", l.history().path("result_list").get(0).path("ord_stat").asText());
        assertEquals(2, l.history().path("result_list").get(0).path("cntr_qty").asInt());
        assertEquals(3, l.history().path("result_list").get(0).path("cncl_qty").asInt());
    }

    @Test
    void sharedQuoteCapacityAndReservationsPreventOverbuy() {
        var l = cashLedger(0);
        l.submit("BUY", "ND", "TEST", 4, new BigDecimal("100"), false);
        l.submit("BUY", "ND", "TEST", 4, new BigDecimal("100"), false);
        assertEquals(
                1,
                l.submit("BUY", "ND", "TEST", 2, new BigDecimal("100"), false)
                        .path("return_code")
                        .asInt());
        l.advanceFrame(frame("partial", START.plusSeconds(30), "99", "100", 5));
        assertEquals(5, l.balance().path("result_list").get(0).path("poss_qty").asInt());
        amount("500", l.report(), "cash_usd");
        amount("300", l.report(), "reserved_cash_usd");
        assertEquals(2, l.report().path("fills").size());
    }

    @Test
    void existingHoldingsCanSellButCannotOversellOrReuseUnsettledProceeds() {
        var initial = initial("0");
        initial.withArray("holdings")
                .addObject()
                .put("symbol", "TEST")
                .put("exchange", "ND")
                .put("quantity", 5)
                .put("average_price", 80);
        var l = new KiwoomUsReplayLedger(initial, model(10));
        l.advanceFrame(frame("initial", START, "99", "100", 100));
        l.submit("SELL", "ND", "TEST", 5, BigDecimal.ZERO, true);
        assertEquals(
                1,
                l.submit("SELL", "ND", "TEST", 1, BigDecimal.ZERO, true)
                        .path("return_code")
                        .asInt());
        assertEquals(0, l.balance().path("result_list").get(0).path("sell_alowq").asInt());
        l.advanceFrame(frame("sold", START.plusSeconds(30), "99", "100", 100));
        amount("0", l.report(), "cash_usd");
        amount("494.505", l.report(), "unsettled_cash_usd");
        amount("494.505", l.report(), "equity_usd");
        assertTrue(l.report().path("holdings").isEmpty());
        assertEquals(
                1,
                l.submit("BUY", "ND", "TEST", 1, new BigDecimal("100"), false)
                        .path("return_code")
                        .asInt());
        l.advanceFrame(frame("settled", Instant.parse("2026-11-18T14:30:00Z"), "99", "100", 100));
        amount("494.505", l.report(), "cash_usd");
        amount("0", l.report(), "unsettled_cash_usd");
    }

    @Test
    void realTradeParsersAgreeWithEquityBeforeAfterReservationAndSettlement() {
        var l = cashLedger(10);
        var trade = mock(KiwoomUsTradeService.class, CALLS_REAL_METHODS);
        l.submit("BUY", "ND", "TEST", 5, new BigDecimal("100"), false);
        assertEquals(
                0,
                trade.totalAccountEquity(l.deposit(), l.balance())
                        .compareTo(l.report().path("equity_usd").decimalValue()));
        l.advanceFrame(frame("bought", START.plusSeconds(30), "99", "100", 100));
        l.submit("SELL", "ND", "TEST", 5, BigDecimal.ZERO, true);
        l.advanceFrame(frame("sold", START.plusSeconds(60), "98", "99", 100));
        assertEquals(
                0,
                trade.totalAccountEquity(l.deposit(), l.balance())
                        .compareTo(l.report().path("equity_usd").decimalValue()));
        l.advanceFrame(frame("settled", Instant.parse("2026-11-18T14:30:00Z"), "98", "99", 100));
        assertEquals(
                0,
                trade.totalAccountEquity(l.deposit(), l.balance())
                        .compareTo(l.report().path("equity_usd").decimalValue()));
    }

    @Test
    void oldQuoteAndLimitAwayFromMarketCannotFill() {
        var l = cashLedger(0);
        l.submit("BUY", "ND", "TEST", 5, new BigDecimal("100"), false);
        var old = frame("old", START.plusSeconds(30), "99", "100", 100);
        old.withObject("/quotes/TEST").put("available_at", START.toString());
        l.advanceFrame(old);
        assertTrue(l.report().path("fills").isEmpty());
        l.advanceFrame(frame("price-away", START.plusSeconds(60), "101", "102", 100));
        assertTrue(l.report().path("fills").isEmpty());
    }

    @Test
    void invalidFramesDoNotMutateOrSettleAndMissingValuationFailsClosed() {
        var l = cashLedger(0);
        l.submit("BUY", "ND", "TEST", 5, new BigDecimal("100"), false);
        var before = l.report();
        var missing = frame("missing", START.plusSeconds(30), "99", "100", 100);
        missing.withObject("/quotes").remove("TEST");
        assertThrows(IllegalArgumentException.class, () -> l.advanceFrame(missing));
        var stale = frame("stale", START.plusSeconds(100), "99", "100", 100);
        stale.withObject("/quotes/TEST").put("available_at", START.toString());
        assertThrows(IllegalArgumentException.class, () -> l.advanceFrame(stale));
        var future = frame("future", START.plusSeconds(30), "99", "100", 100);
        future.withObject("/quotes/TEST").put("available_at", START.plusSeconds(31).toString());
        assertThrows(IllegalArgumentException.class, () -> l.advanceFrame(future));
        assertEquals(before, l.report());
    }

    @Test
    void duplicateFramesRejectedAndReportsCannotMutateInternalState() {
        var l = cashLedger(0);
        var before = l.report();
        assertThrows(
                IllegalArgumentException.class,
                () -> l.advanceFrame(frame("initial", START.plusSeconds(30), "99", "100", 100)));
        assertThrows(
                IllegalArgumentException.class,
                () -> l.advanceFrame(frame("same-time", START, "99", "100", 100)));
        l.report().withArray("events").removeAll();
        assertEquals(before, l.report());
    }

    @Test
    void regularSessionOnlyAndWeekendSettlementUsesTradingCalendar() {
        var initial = initial("0");
        initial.withArray("holdings")
                .addObject()
                .put("symbol", "TEST")
                .put("exchange", "ND")
                .put("quantity", 1)
                .put("average_price", 80);
        var l = new KiwoomUsReplayLedger(initial, model(0));
        Instant friday = Instant.parse("2026-11-20T20:59:00Z");
        l.advanceFrame(frame("fri", friday, "99", "100", 100));
        l.submit("SELL", "ND", "TEST", 1, BigDecimal.ZERO, true);
        l.advanceFrame(frame("fri-fill", friday.plusSeconds(30), "99", "100", 100));
        assertEquals(
                "2026-11-23T14:30:00Z",
                l.report().path("fills").get(0).path("settles_at").asText());
        l.advanceFrame(frame("sat", Instant.parse("2026-11-21T17:00:00Z"), "99", "100", 100));
        amount("0", l.report(), "cash_usd");
        l.advanceFrame(frame("mon-pre", Instant.parse("2026-11-23T14:29:00Z"), "99", "100", 100));
        amount("0", l.report(), "cash_usd");
        l.advanceFrame(frame("mon-open", Instant.parse("2026-11-23T14:30:00Z"), "99", "100", 100));
        amount("99", l.report(), "cash_usd");
        var buy = new KiwoomUsReplayLedger(initial("1000"), model(0));
        buy.advanceFrame(frame("pre", Instant.parse("2026-11-17T14:00:00Z"), "99", "100", 100));
        buy.submit("BUY", "ND", "TEST", 1, new BigDecimal("100"), false);
        buy.advanceFrame(frame("pre2", Instant.parse("2026-11-17T14:00:30Z"), "99", "100", 100));
        assertTrue(buy.report().path("fills").isEmpty());
    }

    @Test
    void unknownInitialFieldsUnsupportedSettlementAndNegativeFeeRejected() {
        var unsupported = initial("1000").put("credit_usd", 100);
        assertThrows(
                IllegalArgumentException.class,
                () -> new KiwoomUsReplayLedger(unsupported, model(0)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new KiwoomUsReplayLedger(initial("1000"), model(-1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new KiwoomUsReplayLedger(initial("1000"), model(101)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new KiwoomUsReplayLedger(
                                initial("1000"), model(0).put("settlement", "IMMEDIATE")));
        var l = cashLedger(0);
        assertThrows(
                IllegalArgumentException.class, () -> l.submit("BUY", "ND", "TEST", 1, null, true));
    }

    @Test
    void eveningOrderKeepsServiceCalendarDateIdentityWhenFilledNextRegularSession() {
        var l = new KiwoomUsReplayLedger(initial("1000"), model(0));
        Instant evening = Instant.parse("2026-11-17T01:00:00Z"); // November 16 at 20:00 ET.
        l.advanceFrame(frame("evening", evening, "99", "100", 100));
        l.submit("BUY", "ND", "TEST", 1, new BigDecimal("100"), false);
        assertEquals("20261116", l.openOrders().path("result_list").get(0).path("ord_dt").asText());
        l.advanceFrame(frame("open", Instant.parse("2026-11-17T14:30:00Z"), "99", "100", 100));
        var historical = l.history().path("result_list").get(0);
        assertEquals("20261116", historical.path("ord_dt").asText());
        assertEquals(1, historical.path("cntr_qty").asInt());
        assertEquals("FILLED", historical.path("ord_stat").asText());
        amount("900", l.report(), "cash_usd");
    }

    @Test
    void observationCannotMoveBackwardAfterSymbolTemporarilyLeavesFrame() {
        var l = cashLedger(0);
        var empty = frame("empty", START.plusSeconds(30), "99", "100", 1);
        empty.withObject("/quotes").remove("TEST");
        l.advanceFrame(empty);
        var old = frame("reappear", START.plusSeconds(60), "99", "100", 100);
        old.withObject("/quotes/TEST").put("available_at", START.minusSeconds(1).toString());
        assertThrows(IllegalArgumentException.class, () -> l.advanceFrame(old));
    }
}
