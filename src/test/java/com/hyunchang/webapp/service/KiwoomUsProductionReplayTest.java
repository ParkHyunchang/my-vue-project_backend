package com.hyunchang.webapp.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.hyunchang.webapp.util.KiwoomUsMarketHours;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

class KiwoomUsProductionReplayTest {
    private static final ArrayNode REPORTS = KiwoomUsProductionReplayHarness.JSON.createArrayNode();
    private static final Instant START = Instant.parse("2026-11-17T17:00:00Z");

    static ObjectNode input() {
        var json = KiwoomUsProductionReplayHarness.JSON;
        ObjectNode input =
                json.createObjectNode()
                        .put("schema_version", 1)
                        .put("dataset_kind", "synthetic")
                        .put("initialization", "NEW_POLICY_NO_PRIOR_AUTOMATION")
                        .put("initial_risk_equity_usd", 10000);
        ObjectNode s = input.putObject("settings");
        s.put("maxOrderPercent", 10)
                .put("maxPositions", 3)
                .put("dailyMaxBuys", 2)
                .put("minChangePercent", 2)
                .put("maxChangePercent", 8)
                .put("minVolumeRatio", 1.2)
                .put("fundamentalFilterEnabled", true)
                .put("maxForwardPe", 50)
                .put("minRoePercent", 10)
                .put("maxSpreadPercent", 0.15)
                .put("stopLossPercent", 3)
                .put("takeProfitPercent", 5)
                .put("takeProfitPercent2", 8)
                .put("maxHoldingDays", 5)
                .put("symbolCooldownDays", 5)
                .put("dailyLossLimitPercent", 3)
                .put("signalMode", "TREND")
                .put("minRelativeStrengthPercent", 0)
                .put("riskPerTradePercent", 0.5)
                .put("atrStopMultiplier", 2)
                .put("maxEntryExtensionAtr", 1)
                .put("trailingStopAtrMultiplier", 2)
                .put("trailingActivationR", 1)
                .put("maxHoldingTradingDays", 5);
        input.putArray("frames").add(frame("initial", START, 10000, "DECIDE"));
        return input;
    }

    static ObjectNode frame(String id, Instant at, double cash, String action) {
        var json = KiwoomUsProductionReplayHarness.JSON;
        ObjectNode f =
                json.createObjectNode()
                        .put("id", id)
                        .put("at", at.toString())
                        .put("observed_at", at.toString());
        f.putArray("actions").add(action);
        ObjectNode account = f.putObject("account");
        account.set("deposit", deposit(cash));
        account.set("balance", balance());
        f.putArray("rankings")
                .addObject()
                .put("rank", 1)
                .put("exchange", "ND")
                .put("symbol", "TEST")
                .put("name", "Synthetic TEST")
                .put("currentPrice", 130.5)
                .put("changePercent", 3)
                .put("accumulatedVolume", 2000000)
                .put("previousVolume", 1000000)
                .put("tradedValue", 100000000);
        f.putObject("quotes")
                .putObject("TEST")
                .put("bid", 130.5)
                .put("ask", 130.5)
                .put("available_at", at.toString());
        f.putObject("fundamentals")
                .putObject("TEST")
                .put("lookup_status", "CACHE_FRESH")
                .put("forwardPe", 20)
                .put("trailingPe", 22)
                .put("roePercent", 20)
                .put("available_at", at.toString());
        f.putObject("universe")
                .putObject("TEST")
                .put("eligible", true)
                .put("label", "SYNTHETIC_INDEX")
                .put("available_at", at.toString());
        ObjectNode bars = f.putObject("completed_bars");
        List<LocalDate> dates = new ArrayList<>();
        LocalDate d = at.atZone(KiwoomUsMarketHours.ET).toLocalDate();
        for (int i = 0; i < 61; i++) {
            d = KiwoomUsMarketHours.previousTradingDay(d);
            dates.add(d);
        }
        Collections.reverse(dates);
        for (String symbol : List.of("TEST", "SPY", "QQQ")) {
            ArrayNode rows = bars.putArray(symbol);
            for (int i = 0; i < dates.size(); i++) {
                double p = 100 + i * (symbol.equals("TEST") ? .5 : symbol.equals("SPY") ? .1 : .15);
                rows.addObject()
                        .put("date", dates.get(i).toString())
                        .put("open", p)
                        .put("high", p + .5)
                        .put("low", p - .5)
                        .put("close", p)
                        .put("volume", 1000000);
            }
        }
        ObjectNode broker = f.putObject("broker");
        broker.putObject("open_orders").putArray("result_list");
        broker.putObject("fills").putArray("result_list");
        broker.putObject("history").putArray("result_list");
        broker.putObject("orderability").set("TEST", orderability(cash));
        broker.putArray("write_responses");
        return f;
    }

    static ObjectNode deposit(double cash) {
        ObjectNode d =
                KiwoomUsProductionReplayHarness.JSON
                        .createObjectNode()
                        .put("return_code", 0)
                        .put("d0_usd_fx_entr", cash)
                        .put("krw_ord_set_amt", 0)
                        .put("won_dfr_amt", 0)
                        .put("won_etc_loana", 0);
        for (int i = 1; i <= 4; i++)
            d.put("d" + i + "_usd_fx_entr", cash)
                    .put("d" + i + "_usd_sell_excta", 0)
                    .put("d" + i + "_usd_buy_excta", 0);
        return d;
    }

    static ObjectNode orderability(double cash) {
        return KiwoomUsProductionReplayHarness.JSON
                .createObjectNode()
                .put("return_code", 0)
                .put("krw_ord_rqst_yn", "N")
                .put("krw_ord_set_amt", 0)
                .put("krw_ord_alowa_100", 0)
                .put("krw_ord_alowq_100", 0)
                .put("min_ord_alowq", (int) (cash / 130.5))
                .put("fc_entra", cash)
                .put("fc_uncl_amt", 0)
                .put("ord_alowa", cash)
                .put("min_ord_alowa", cash);
    }

    static ObjectNode balance() {
        var b =
                KiwoomUsProductionReplayHarness.JSON
                        .createObjectNode()
                        .put("return_code", 0)
                        .put("crnc_code", "USD")
                        .put("tot_evlt_amt", 0);
        b.putArray("result_list");
        return b;
    }

    static void holding(ObjectNode f, int quantity, double average, double price) {
        ObjectNode b = balance();
        b.put("tot_evlt_amt", quantity * price);
        b.withArray("result_list")
                .addObject()
                .put("stk_cd", "TEST")
                .put("stex_cd", "ND")
                .put("crnc_code", "USD")
                .put("frgn_stk_nm", "Synthetic TEST")
                .put("poss_qty", quantity)
                .put("sell_alowq", quantity)
                .put("avg_pric", average)
                .put("now_pric", price)
                .put("evlt_amt", quantity * price)
                .put("pl_amt", quantity * (price - average))
                .put("pl_rt", (price / average - 1) * 100);
        ((ObjectNode) f.path("account")).set("balance", b);
        ((ObjectNode) f.path("quotes").path("TEST")).put("bid", price).put("ask", price);
    }

    static void response(ObjectNode f, String api, String orderNo) {
        f.withObject("/broker")
                .withArray("write_responses")
                .addObject()
                .put("api_id", api)
                .putObject("response")
                .put("return_code", 0)
                .put("ord_no", orderNo);
    }

    static void fill(
            ObjectNode f, String no, int filled, int remaining, double price, boolean open) {
        ObjectNode row =
                KiwoomUsProductionReplayHarness.JSON
                        .createObjectNode()
                        .put("ord_no", no)
                        .put("ord_dt", "20261117")
                        .put("stk_cd", "TEST")
                        .put("slby_tp", "2")
                        .put("cntr_qty", filled)
                        .put("oso_qty", remaining)
                        .put("cntr_uv", price)
                        .put("ord_qty", filled + remaining)
                        .put("ord_uv", 130.5);
        f.withObject("/broker").withObject("/fills").withArray("result_list").add(row.deepCopy());
        if (open)
            f.withObject("/broker")
                    .withObject("/open_orders")
                    .withArray("result_list")
                    .add(row.deepCopy());
    }

    private ObjectNode run(String name, ObjectNode data) {
        assertNotNull(System.getSecurityManager(), "Run only through the isolated test profile");
        assertTrue(System.getSecurityManager().getClass().getName().contains("TestNetworkGuard"));
        try (var harness = new KiwoomUsProductionReplayHarness(data)) {
            ObjectNode report = harness.run();
            report.put("scenario", name);
            REPORTS.add(report);
            return report;
        }
    }

    @Test
    void realDecisionBuildsCandidateSizesBuyAndFinalFundingGuardSendsOnlyFakeWrite() {
        ObjectNode data = input();
        response((ObjectNode) data.path("frames").get(0), "ust20000", "B1");
        var r = run("normal_actual_service_buy", data);
        assertEquals("ORDERED", r.path("trace").get(0).path("decision").path("status").asText());
        assertEquals(1, r.path("broker_boundary_writes").size());
        assertEquals(7, r.path("attempted_orders").get(0).path("quantity").asInt());
        assertEquals("BUY", r.path("attempted_orders").get(0).path("side").asText());
    }

    @Test
    void finalOrderabilityCanRejectAnOtherwiseAcceptedCandidate() {
        ObjectNode data = input();
        ObjectNode f = (ObjectNode) data.path("frames").get(0);
        f.withObject("/broker").withObject("/orderability").set("TEST", orderability(1));
        var r = run("final_cash_guard", data);
        assertEquals(1, r.path("attempted_orders").size());
        assertEquals(0, r.path("broker_boundary_writes").size());
        assertEquals("SKIPPED", r.path("trace").get(0).path("decision").path("status").asText());
    }

    @Test
    void preexistingManualHoldingUsesActualFullAccountExitPath() {
        ObjectNode data = input();
        ObjectNode f = frame("manual", START, 9000, "MONITOR");
        holding(f, 10, 100, 90);
        response(f, "ust20001", "S1");
        data.withArray("frames").removeAll().add(f);
        var r = run("preexisting_manual_exit", data);
        assertEquals("SELL", r.path("attempted_orders").get(0).path("side").asText());
        assertEquals(10, r.path("attempted_orders").get(0).path("quantity").asInt());
    }

    @Test
    void actualFillReconciliationProducesMixedHoldingAndWholePositionExit() {
        ObjectNode data = input();
        response((ObjectNode) data.path("frames").get(0), "ust20000", "B1");
        ObjectNode f = frame("mixed_after_fill", START.plusSeconds(30), 9000, "MONITOR");
        holding(f, 10, 130.5, 120);
        fill(f, "B1", 7, 0, 130.5, false);
        response(f, "ust20001", "S1");
        data.withArray("frames").add(f);
        var r = run("mixed_holding_after_actual_reconciliation", data);
        assertEquals(2, r.path("broker_boundary_writes").size());
        assertEquals(10, r.path("attempted_orders").get(1).path("quantity").asInt());
        assertTrue(
                r.path("trace")
                        .get(1)
                        .path("proposals")
                        .get(1)
                        .path("reason")
                        .asText()
                        .contains("혼합 보유"));
    }

    @Test
    void partialFillAndRepeatedDecisionCannotDuplicatePendingBuy() {
        ObjectNode data = input();
        response((ObjectNode) data.path("frames").get(0), "ust20000", "B1");
        ObjectNode f = frame("partial", START.plusSeconds(30), 9739, "RECONCILE");
        holding(f, 2, 130.5, 130.5);
        fill(f, "B1", 2, 5, 130.5, true);
        f.withArray("actions").add("DECIDE").add("DECIDE");
        data.withArray("frames").add(f);
        var r = run("partial_fill_duplicate_decision", data);
        assertEquals(1, r.path("broker_boundary_writes").size());
        assertEquals(
                2, r.path("trace").get(1).path("proposals").get(0).path("filledQuantity").asInt());
        assertEquals(
                "NO_CANDIDATE", r.path("trace").get(2).path("decision").path("status").asText());
    }

    @Test
    void unknownBrokerAcknowledgementStopsSubsequentDecisionWithoutRetry() {
        ObjectNode data = input();
        ObjectNode f = (ObjectNode) data.path("frames").get(0);
        response(f, "ust20000", "");
        f.withArray("actions").add("DECIDE");
        var r = run("unknown_order_no_retry", data);
        assertEquals(1, r.path("broker_boundary_writes").size());
        assertEquals(
                "UNKNOWN", r.path("trace").get(0).path("proposals").get(0).path("status").asText());
        assertFalse(r.path("trace").get(1).path("autoTrading").asBoolean());
    }

    @Test
    void storedFilledOrderEnforcesActualDailyBuyLimitForADifferentCandidate() {
        ObjectNode data = input();
        data.withObject("/settings").put("dailyMaxBuys", 1);
        response((ObjectNode) data.path("frames").get(0), "ust20000", "B1");
        ObjectNode next =
                frame("new_candidate_same_day", START.plusSeconds(30), 9086.5, "RECONCILE");
        holding(next, 7, 130.5, 130.5);
        fill(next, "B1", 7, 0, 130.5, false);
        ((ObjectNode) next.path("rankings").get(0)).put("symbol", "NEXT");
        for (String field : List.of("quotes", "fundamentals", "universe", "completed_bars"))
            ((ObjectNode) next.path(field)).set("NEXT", next.path(field).path("TEST").deepCopy());
        next.withObject("/broker").withObject("/orderability").set("NEXT", orderability(9086.5));
        next.withArray("actions").add("DECIDE");
        data.withArray("frames").add(next);
        var r = run("actual_daily_buy_counter", data);
        assertEquals(1, r.path("broker_boundary_writes").size());
        var decision = r.path("trace").get(2).path("decision");
        assertEquals("SKIPPED", decision.path("status").asText());
        assertTrue(decision.path("message").asText().contains("매수 횟수 한도"));
    }

    @Test
    void explicitUnavailableFundamentalsExerciseTheActualTrendFallback() {
        ObjectNode data = input();
        ObjectNode first = (ObjectNode) data.path("frames").get(0);
        ObjectNode fundamental = (ObjectNode) first.path("fundamentals").path("TEST");
        fundamental.put("lookup_status", "DATA_UNAVAILABLE");
        fundamental.remove(List.of("forwardPe", "trailingPe", "roePercent"));
        response(first, "ust20000", "B1");
        var r = run("actual_trend_unavailable_fundamental_fallback", data);
        assertEquals("ORDERED", r.path("trace").get(0).path("decision").path("status").asText());
        assertEquals(1, r.path("broker_boundary_writes").size());
    }

    @Test
    void actualAccountRiskLatchesLiquidatesAndCannotResumeAfterRebound() {
        ObjectNode data = input();
        ObjectNode f = frame("breach", START, 8000, "MONITOR");
        holding(f, 10, 100, 90);
        response(f, "ust20001", "S1");
        data.withArray("frames").removeAll().add(f);
        ObjectNode rebound = frame("rebound", START.plusSeconds(30), 11000, "DECIDE");
        data.withArray("frames").add(rebound);
        var r = run("ten_percent_whole_account_latch", data);
        assertTrue(r.path("trace").get(0).path("risk").path("triggered").asBoolean());
        assertTrue(r.path("trace").get(1).path("risk").path("triggered").asBoolean());
        assertEquals(1, r.path("broker_boundary_writes").size());
        assertEquals("SELL", r.path("attempted_orders").get(0).path("side").asText());
    }

    @Test
    void realCalendarRejectsWeekendWithoutOverridingMarketOpen() {
        ObjectNode data = input();
        data.withArray("frames")
                .removeAll()
                .add(frame("weekend", Instant.parse("2026-11-21T17:00:00Z"), 10000, "DECIDE"));
        var r = run("real_weekend_calendar", data);
        assertEquals("SKIPPED", r.path("trace").get(0).path("decision").path("status").asText());
        assertEquals(0, r.path("attempted_orders").size());
    }

    @Test
    void missingSettingsFutureQuotesAndIncompleteBarsFailBeforeAnyDecision() {
        ObjectNode missing = input();
        missing.withObject("/settings").remove("riskPerTradePercent");
        assertThrows(
                IllegalArgumentException.class,
                () -> KiwoomUsProductionReplayHarness.validate(missing));
        ObjectNode future = input();
        ((ObjectNode) future.path("frames").get(0).path("quotes").path("TEST"))
                .put("available_at", START.plusSeconds(1).toString());
        assertThrows(
                IllegalArgumentException.class,
                () -> KiwoomUsProductionReplayHarness.validate(future));
        ObjectNode bars = input();
        ((ObjectNode) bars.path("frames").get(0).path("completed_bars").path("TEST").get(60))
                .put("date", "2026-11-17");
        assertThrows(
                IllegalArgumentException.class,
                () -> KiwoomUsProductionReplayHarness.validate(bars));
        ObjectNode existing = input();
        existing.put("initialization", "RESTORE_EXISTING_POLICY");
        assertThrows(
                IllegalArgumentException.class,
                () -> KiwoomUsProductionReplayHarness.validate(existing));
        ObjectNode missingCash = input();
        ((ObjectNode) missingCash.path("frames").get(0).path("account").path("deposit"))
                .remove("d4_usd_fx_entr");
        assertThrows(
                IllegalArgumentException.class,
                () -> KiwoomUsProductionReplayHarness.validate(missingCash));
    }

    @Test
    void replayExplicitInput() throws Exception {
        String source = System.getProperty("us.replay.input");
        assumeTrue(source != null, "Explicit replay input is optional");
        Path path = Path.of(source).toAbsolutePath().normalize();
        if (Files.size(path) > 50_000_000)
            throw new IllegalArgumentException("Replay input exceeds 50MB");
        JsonNode data = KiwoomUsProductionReplayHarness.JSON.readTree(Files.readAllBytes(path));
        var result = run("explicit_input", (ObjectNode) data);
        result.put(
                "input_sha256",
                HexFormat.of()
                        .formatHex(
                                java.security.MessageDigest.getInstance("SHA-256")
                                        .digest(Files.readAllBytes(path))));
    }

    @AfterAll
    static void writeReport() throws Exception {
        String output = System.getProperty("us.replay.output");
        if (output == null) return;
        ObjectNode report =
                KiwoomUsProductionReplayHarness.JSON
                        .createObjectNode()
                        .put("schema_version", 1)
                        .put("execution_mode", "OFFLINE_ACTUAL_SERVICES")
                        .put("real_order_count", 0)
                        .put("performance_verified", false)
                        .put(
                                "test_result_authority",
                                "Check JUnit/Maven exit status; a report alone is not proof all assertions passed.");
        report.putNull("return_pct");
        report.putNull("benchmark_return_pct");
        report.set("production_sha256", KiwoomUsProductionReplayHarness.productionHashes());
        report.set("scenarios", REPORTS);
        Path path = Path.of(output).toAbsolutePath().normalize();
        String source = System.getProperty("us.replay.input");
        if (source != null && path.equals(Path.of(source).toAbsolutePath().normalize()))
            throw new IllegalArgumentException("Cannot overwrite replay input");
        Files.createDirectories(path.getParent());
        Files.writeString(
                path,
                KiwoomUsProductionReplayHarness.JSON
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(report));
        if (source == null) {
            ObjectNode sample = input();
            response((ObjectNode) sample.path("frames").get(0), "ust20000", "B1");
            Files.writeString(
                    path.getParent().resolve("synthetic-input.json"),
                    KiwoomUsProductionReplayHarness.JSON
                            .writerWithDefaultPrettyPrinter()
                            .writeValueAsString(sample));
        }
    }
}
