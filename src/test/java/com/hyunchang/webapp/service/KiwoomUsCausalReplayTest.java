package com.hyunchang.webapp.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

class KiwoomUsCausalReplayTest {
    private static final Instant START = Instant.parse("2026-11-17T17:00:00Z");
    private static final ArrayNode REPORTS = KiwoomUsProductionReplayHarness.JSON.createArrayNode();

    static ObjectNode input() {
        ObjectNode data = KiwoomUsProductionReplayTest.input();
        data.remove("initial_risk_equity_usd");
        data.put("execution_mode", "CAUSAL_LEDGER");
        data.putObject("ledger_initial").put("cash_usd", 10000).putArray("holdings");
        data.putObject("execution_model").put("fee_bps", 10).put("settlement", "NEXT_TRADING_DAY");
        data.withArray("frames").removeAll().add(frame("decision", START, 130.5, 100, "DECIDE"));
        return data;
    }

    static ObjectNode frame(String id, Instant at, double price, int size, String action) {
        ObjectNode f = KiwoomUsProductionReplayTest.frame(id, at, 0, action);
        f.remove(List.of("account", "broker"));
        f.withObject("/quotes/TEST")
                .put("bid", price)
                .put("ask", price)
                .put("bid_size", size)
                .put("ask_size", size);
        return f;
    }

    private static void manual(ObjectNode data, int quantity, double average) {
        data.withObject("/ledger_initial")
                .withArray("holdings")
                .addObject()
                .put("symbol", "TEST")
                .put("exchange", "ND")
                .put("quantity", quantity)
                .put("average_price", average);
    }

    private ObjectNode run(String name, ObjectNode data) {
        if (!"CAUSAL_LEDGER".equals(data.path("execution_mode").asText()))
            throw new IllegalArgumentException(
                    "Causal runner requires execution_mode=CAUSAL_LEDGER");
        assertNotNull(System.getSecurityManager(), "Use isolated profile only");
        assertTrue(System.getSecurityManager().getClass().getName().contains("TestNetworkGuard"));
        try (var harness = new KiwoomUsProductionReplayHarness(data)) {
            ObjectNode report = harness.run();
            report.put("scenario", name);
            REPORTS.add(report);
            return report;
        }
    }

    private static JsonNode last(ObjectNode r) {
        return r.path("trace").get(r.path("trace").size() - 1);
    }

    private static void money(String expected, JsonNode actual) {
        assertTrue(actual.isNumber(), actual.toString());
        assertEquals(
                0, new BigDecimal(expected).compareTo(actual.decimalValue()), actual.toString());
    }

    @Test
    void actualBuyFillsOnlyOnLaterFrameAndFeedsCashAndHoldingBackIntoDecision() {
        ObjectNode data = input();
        ObjectNode next = frame("fill", START.plusSeconds(30), 130.5, 100, "RECONCILE");
        next.withArray("actions").add("DECIDE");
        data.withArray("frames").add(next);
        var r = run("causal_buy_cash_and_next_decision", data);
        assertEquals(1, r.path("attempted_orders").size());
        assertEquals(7, r.path("attempted_orders").get(0).path("quantity").asInt());
        money("10000", r.path("trace").get(0).path("ledger").path("cash_usd"));
        money("9085.5865", r.path("ledger").path("cash_usd"));
        assertEquals(7, last(r).path("proposals").get(0).path("filledQuantity").asInt());
        assertEquals("NO_CANDIDATE", last(r).path("decision").path("status").asText());
        assertFalse(r.path("performance_verified").asBoolean());
    }

    @Test
    void partialFillReservesRemainderAndRepeatedDecisionsDoNotCreateAnotherBuy() {
        ObjectNode data = input();
        ObjectNode partial = frame("partial", START.plusSeconds(30), 130.5, 2, "RECONCILE");
        partial.withArray("actions").add("DECIDE").add("DECIDE");
        data.withArray("frames").add(partial);
        var r = run("causal_partial_and_reservation", data);
        assertEquals(1, r.path("attempted_orders").size());
        assertEquals(2, last(r).path("proposals").get(0).path("filledQuantity").asInt());
        money("9738.739", r.path("ledger").path("cash_usd"));
        assertTrue(r.path("ledger").path("reserved_cash_usd").decimalValue().signum() > 0);
        assertTrue(r.path("ledger").path("available_cash_usd").decimalValue().signum() >= 0);
    }

    @Test
    void crossingLimitRequiredAndMissingLiquidityDoesNotInventFill() {
        ObjectNode data = input();
        data.withArray("frames")
                .add(frame("gap_above_limit", START.plusSeconds(30), 131, 100, "RECONCILE"));
        data.withArray("frames")
                .add(frame("no_liquidity", START.plusSeconds(60), 130.5, 0, "RECONCILE"));
        var r = run("causal_limit_and_zero_liquidity", data);
        money("10000", r.path("ledger").path("cash_usd"));
        assertEquals(0, last(r).path("proposals").get(0).path("filledQuantity").asInt());
    }

    @Test
    void manuallyOwnedStockSellsAndProceedsRemainUnsettledUntilNextTradingDay() {
        ObjectNode data = input();
        manual(data, 10, 100);
        data.withObject("/ledger_initial").put("cash_usd", 9000);
        data.withArray("frames").removeAll().add(frame("manual_exit", START, 90, 100, "MONITOR"));
        data.withArray("frames")
                .add(frame("manual_fill", START.plusSeconds(30), 90, 100, "MONITOR"));
        data.withArray("frames")
                .add(frame("settle", START.plusSeconds(86400), 90, 100, "RECONCILE"));
        var r = run("causal_manual_sell_and_settlement", data);
        assertEquals("SELL", r.path("attempted_orders").get(0).path("side").asText());
        assertEquals(10, r.path("attempted_orders").get(0).path("quantity").asInt());
        money("9000", r.path("trace").get(1).path("ledger").path("cash_usd"));
        money("899.1", r.path("trace").get(1).path("ledger").path("unsettled_cash_usd"));
        money("9899.1", r.path("ledger").path("cash_usd"));
        money("0", r.path("ledger").path("unsettled_cash_usd"));
    }

    @Test
    void actualWholeAccountTenPercentRiskCancelsNewBuyingAndRemainsLatchedAfterRebound() {
        ObjectNode data = input();
        data.withObject("/ledger_initial").put("cash_usd", 0);
        manual(data, 100, 100);
        data.withArray("frames").removeAll().add(frame("baseline", START, 100, 100, "MONITOR"));
        data.withArray("frames").add(frame("ten_percent", START.plusSeconds(30), 90, 0, "MONITOR"));
        data.withArray("frames").add(frame("rebound", START.plusSeconds(60), 110, 100, "MONITOR"));
        ObjectNode finalFrame = frame("blocked", START.plusSeconds(90), 110, 100, "DECIDE");
        data.withArray("frames").add(finalFrame);
        var r = run("causal_ten_percent_latch", data);
        assertTrue(r.path("trace").get(1).path("risk").path("triggered").asBoolean());
        assertTrue(last(r).path("risk").path("triggered").asBoolean());
        assertFalse(last(r).path("autoTrading").asBoolean());
        assertTrue(r.path("attempted_orders").size() > 0);
        for (var order : r.path("attempted_orders"))
            assertEquals("SELL", order.path("side").asText());
    }

    @Test
    void explicitInitialCashIsTheOnlyPurchaseBudget() {
        ObjectNode data = input();
        data.withObject("/ledger_initial").put("cash_usd", 1);
        var r = run("causal_insufficient_cash", data);
        assertEquals(0, r.path("broker_boundary_writes").size());
        money("1", r.path("ledger").path("cash_usd"));
    }

    @Test
    void actualServiceCancelsExpiredPendingBuyAndReconciliationReleasesReservation() {
        ObjectNode data = input();
        data.withArray("frames").add(frame("timeout", START.plusSeconds(91), 131, 0, "RECONCILE"));
        data.withArray("frames")
                .add(frame("cancel_confirmed", START.plusSeconds(121), 131, 100, "RECONCILE"));
        var r = run("causal_actual_cancel_and_reconcile", data);
        assertEquals(2, r.path("broker_boundary_writes").size());
        assertEquals("ust20003", r.path("broker_boundary_writes").get(1).path("api_id").asText());
        assertEquals("CANCELED", last(r).path("proposals").get(0).path("status").asText());
        money("0", r.path("ledger").path("reserved_cash_usd"));
        money("10000", r.path("ledger").path("cash_usd"));
    }

    @Test
    void actualAcquiredHoldingStopLossCreatesSellAndUpdatesCausalAccount() {
        ObjectNode data = input();
        data.withArray("frames")
                .add(frame("buy_fill", START.plusSeconds(30), 130.5, 100, "MONITOR"));
        data.withArray("frames").add(frame("stop", START.plusSeconds(60), 120, 100, "MONITOR"));
        data.withArray("frames")
                .add(frame("sell_fill", START.plusSeconds(90), 120, 100, "MONITOR"));
        var r = run("causal_buy_stop_and_sell", data);
        assertEquals(2, r.path("attempted_orders").size());
        assertEquals("BUY", r.path("attempted_orders").get(0).path("side").asText());
        assertEquals("SELL", r.path("attempted_orders").get(1).path("side").asText());
        assertEquals(7, r.path("attempted_orders").get(1).path("quantity").asInt());
        money("9085.5865", r.path("ledger").path("cash_usd"));
        money("839.16", r.path("ledger").path("unsettled_cash_usd"));
        assertEquals(7, last(r).path("proposals").get(1).path("filledQuantity").asInt());
    }

    @Test
    void actualCalendarRejectsWeekendCausalDecision() {
        ObjectNode data = input();
        data.withArray("frames")
                .removeAll()
                .add(frame("weekend", Instant.parse("2026-11-21T17:00:00Z"), 130.5, 100, "DECIDE"));
        var r = run("causal_weekend", data);
        assertEquals(0, r.path("broker_boundary_writes").size());
        money("10000", r.path("ledger").path("cash_usd"));
    }

    @Test
    void replayIsDeterministic() {
        ObjectNode first = run("deterministic", input());
        ObjectNode second = run("deterministic", input());
        assertEquals(first, second);
    }

    @Test
    void callerCannotInjectLaterCashOrBrokerFillsOrRiskBaseline() {
        ObjectNode account = input();
        ((ObjectNode) account.path("frames").get(0)).putObject("account");
        assertThrows(
                IllegalArgumentException.class, () -> new KiwoomUsProductionReplayHarness(account));
        ObjectNode broker = input();
        ((ObjectNode) broker.path("frames").get(0)).putObject("broker");
        assertThrows(
                IllegalArgumentException.class, () -> new KiwoomUsProductionReplayHarness(broker));
        ObjectNode risk = input();
        risk.put("initial_risk_equity_usd", 1000000);
        assertThrows(
                IllegalArgumentException.class, () -> new KiwoomUsProductionReplayHarness(risk));
    }

    @Test
    void staleOrFutureQuotesAndMissingLiquidityFailBeforeDecisions() {
        for (int seconds : new int[] {-91, 1}) {
            ObjectNode data = input();
            ((ObjectNode) data.path("frames").get(0).path("quotes").path("TEST"))
                    .put("available_at", START.plusSeconds(seconds).toString());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new KiwoomUsProductionReplayHarness(data));
        }
        ObjectNode missing = input();
        ((ObjectNode) missing.path("frames").get(0).path("quotes").path("TEST")).remove("ask_size");
        assertThrows(
                IllegalArgumentException.class, () -> new KiwoomUsProductionReplayHarness(missing));
    }

    @Test
    void replayExplicitInput() throws Exception {
        String source = System.getProperty("us.causal.input");
        assumeTrue(source != null, "Explicit causal input optional");
        Path path = Path.of(source).toAbsolutePath().normalize();
        if (Files.size(path) > 20_000_000) throw new IllegalArgumentException("Input exceeds 20MB");
        byte[] bytes = Files.readAllBytes(path);
        ObjectNode r =
                run(
                        "explicit_causal_input",
                        (ObjectNode) KiwoomUsProductionReplayHarness.JSON.readTree(bytes));
        r.put(
                "input_sha256",
                HexFormat.of()
                        .formatHex(
                                java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
    }

    @AfterAll
    static void report() throws Exception {
        String output = System.getProperty("us.causal.output");
        if (output == null) return;
        Path path = Path.of(output).toAbsolutePath().normalize();
        String source = System.getProperty("us.causal.input");
        if (source != null && path.equals(Path.of(source).toAbsolutePath().normalize()))
            throw new IllegalArgumentException("Cannot overwrite input");
        ObjectNode report =
                KiwoomUsProductionReplayHarness.JSON
                        .createObjectNode()
                        .put("schema_version", 1)
                        .put("execution_mode", "OFFLINE_CAUSAL_ACTUAL_SERVICES")
                        .put("real_order_count", 0)
                        .put("performance_verified", false)
                        .put(
                                "test_result_authority",
                                "Check Maven/JUnit exit status; report existence does not prove assertions passed.");
        report.putNull("return_pct");
        report.putNull("benchmark_return_pct");
        report.set("production_sha256", KiwoomUsProductionReplayHarness.productionHashes());
        report.set("scenarios", REPORTS);
        Files.createDirectories(path.getParent());
        Files.writeString(
                path,
                KiwoomUsProductionReplayHarness.JSON
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(report));
        if (source == null) {
            ObjectNode sample = input();
            sample.withArray("frames")
                    .add(frame("fill", START.plusSeconds(30), 130.5, 100, "MONITOR"));
            sample.withArray("frames")
                    .add(frame("next_decision", START.plusSeconds(60), 130.5, 100, "DECIDE"));
            Files.writeString(
                    path.getParent().resolve("synthetic-input.json"),
                    KiwoomUsProductionReplayHarness.JSON
                            .writerWithDefaultPrettyPrinter()
                            .writeValueAsString(sample));
        }
    }
}
