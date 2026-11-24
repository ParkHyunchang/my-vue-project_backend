package com.hyunchang.webapp.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

/** Controlled counterexamples, not historical stock-selection profitability evidence. */
class KiwoomUsSelectionReplayTest {
    private static final Instant START = Instant.parse("2026-11-17T17:00:00Z");
    private static final ArrayNode REPORTS = KiwoomUsProductionReplayHarness.JSON.createArrayNode();
    private static final List<String> SYMBOLS =
            List.of("ALPHA", "BETA", "EXPENSIVE", "OUTSIDE", "WAITING");

    private enum Variant {
        BASE(0, 1),
        TIGHT_EXTENSION(0, .25),
        STRONG_RELATIVE(8, 1);
        final double strength, extension;

        Variant(double strength, double extension) {
            this.strength = strength;
            this.extension = extension;
        }
    }

    private static ObjectNode frame(
            String id, Instant at, double alpha, double beta, String action) {
        ObjectNode f = KiwoomUsCausalReplayTest.frame(id, at, 130.5, 100, action);
        JsonNode templateRank = f.path("rankings").get(0).deepCopy();
        JsonNode templateQuote = f.path("quotes").path("TEST").deepCopy();
        JsonNode templateFundamental = f.path("fundamentals").path("TEST").deepCopy();
        JsonNode templateUniverse = f.path("universe").path("TEST").deepCopy();
        JsonNode templateBars = f.path("completed_bars").path("TEST").deepCopy();
        f.withArray("rankings").removeAll();
        for (String section : List.of("quotes", "fundamentals", "universe", "completed_bars"))
            f.withObject("/" + section).remove("TEST");
        for (int index = 0; index < SYMBOLS.size(); index++) {
            String symbol = SYMBOLS.get(index);
            double price =
                    symbol.equals("ALPHA")
                            ? alpha
                            : symbol.equals("BETA") ? beta : symbol.equals("WAITING") ? 125 : 161.1;
            ObjectNode rank = templateRank.deepCopy();
            rank.put("rank", index + 1)
                    .put("symbol", symbol)
                    .put("name", "Synthetic " + symbol)
                    .put("currentPrice", price);
            f.withArray("rankings").add(rank);
            ObjectNode quote = templateQuote.deepCopy();
            quote.put("bid", price).put("ask", price);
            f.withObject("/quotes").set(symbol, quote);
            ObjectNode fundamental = templateFundamental.deepCopy();
            if (symbol.equals("EXPENSIVE"))
                fundamental.put("forwardPe", 100).put("trailingPe", 100);
            f.withObject("/fundamentals").set(symbol, fundamental);
            ObjectNode universe = templateUniverse.deepCopy();
            if (symbol.equals("OUTSIDE")) universe.put("eligible", false);
            f.withObject("/universe").set(symbol, universe);
            ArrayNode bars = templateBars.deepCopy();
            double slope = symbol.equals("BETA") || symbol.equals("WAITING") ? .5 : 1;
            for (int i = 0; i < bars.size(); i++) {
                double p = 100 + i * slope;
                ((ObjectNode) bars.get(i))
                        .put("open", p)
                        .put("high", p + .5)
                        .put("low", p - .5)
                        .put("close", p);
            }
            f.withObject("/completed_bars").set(symbol, bars);
        }
        return f;
    }

    private static ObjectNode input(Variant variant, boolean continuation) {
        ObjectNode data = KiwoomUsCausalReplayTest.input();
        data.withObject("/settings")
                .put("minRelativeStrengthPercent", variant.strength)
                .put("maxEntryExtensionAtr", variant.extension);
        ArrayNode frames = data.withArray("frames").removeAll();
        frames.add(frame("select", START, 161.1, 130.5, "DECIDE"));
        frames.add(frame("entry_fill", START.plusSeconds(30), 161.1, 130.5, "MONITOR"));
        frames.add(
                frame(
                        "move",
                        START.plusSeconds(60),
                        continuation ? 180 : 150,
                        continuation ? 131 : 145,
                        "MONITOR"));
        frames.add(
                frame(
                        "exit_signal_or_fill",
                        START.plusSeconds(90),
                        continuation ? 175 : 150,
                        continuation ? 127 : 142,
                        "MONITOR"));
        frames.add(
                frame(
                        "exit_fill",
                        START.plusSeconds(120),
                        continuation ? 175 : 150,
                        continuation ? 127 : 142,
                        "MONITOR"));
        frames.add(
                frame(
                        "settled",
                        START.plusSeconds(86400),
                        continuation ? 175 : 150,
                        continuation ? 127 : 142,
                        "MONITOR"));
        return data;
    }

    private static ObjectNode run(
            String scenario, String variant, String marketPath, ObjectNode data) {
        assertNotNull(System.getSecurityManager(), "Use isolated profile only");
        assertTrue(System.getSecurityManager().getClass().getName().contains("TestNetworkGuard"));
        try (var harness = new KiwoomUsProductionReplayHarness(data)) {
            ObjectNode result = harness.run();
            result.put("scenario", scenario)
                    .put("selection_variant", variant)
                    .put("market_path", marketPath)
                    .put("selection_performance_verified", false)
                    .put(
                            "experiment_scope",
                            "Deliberately constructed counterexamples; no historical observations, optimization, or strategy adoption.");
            REPORTS.add(result);
            return result;
        }
    }

    private static BigDecimal equity(JsonNode result) {
        return result.path("ledger").path("equity_usd").decimalValue();
    }

    @Test
    void predeclaredVariantsCanHelpOrHurtDependingOnFuturePath() {
        Map<String, BigDecimal> outcomes = new LinkedHashMap<>();
        for (boolean continuation : new boolean[] {true, false}) {
            String path = continuation ? "CONTINUATION" : "REVERSAL";
            for (Variant variant : Variant.values()) {
                ObjectNode result =
                        run(
                                variant + "_" + path,
                                variant.name(),
                                path,
                                input(variant, continuation));
                String expected = variant == Variant.TIGHT_EXTENSION ? "BETA" : "ALPHA";
                JsonNode attempts = result.path("attempted_orders");
                JsonNode selection = result.path("trace").get(0);
                assertTrue(selection.path("candidate_list_refreshed").asBoolean());
                assertEquals(5, selection.path("selection_input").size());
                assertEquals(variant == Variant.BASE ? 2 : 1, selection.path("candidates").size());
                assertEquals(expected, selection.path("candidates").get(0).path("symbol").asText());
                assertEquals(2, attempts.size(), result.path("audit").toString());
                assertEquals(expected, attempts.get(0).path("symbol").asText());
                assertEquals("BUY", attempts.get(0).path("side").asText());
                assertEquals(expected, attempts.get(1).path("symbol").asText());
                assertEquals("SELL", attempts.get(1).path("side").asText());
                assertEquals(2, result.path("ledger").path("fills").size());
                assertTrue(result.path("ledger").path("holdings").isEmpty());
                assertEquals(
                        0, result.path("ledger").path("reserved_cash_usd").decimalValue().signum());
                assertEquals(
                        0,
                        result.path("ledger").path("unsettled_cash_usd").decimalValue().signum());
                for (JsonNode event : result.path("ledger").path("events")) {
                    if (event.path("type").asText().equals("MARK")) {
                        assertTrue(event.path("cash_usd").decimalValue().signum() >= 0);
                        assertTrue(event.path("available_cash_usd").decimalValue().signum() >= 0);
                    }
                }
                assertFalse(result.path("performance_verified").asBoolean());
                outcomes.put(variant + "_" + path, equity(result));
            }
        }
        assertTrue(outcomes.get("BASE_CONTINUATION").compareTo(new BigDecimal("10000")) > 0);
        assertTrue(outcomes.get("BASE_REVERSAL").compareTo(new BigDecimal("10000")) < 0);
        assertTrue(
                outcomes.get("TIGHT_EXTENSION_CONTINUATION")
                                .compareTo(outcomes.get("BASE_CONTINUATION"))
                        < 0);
        assertTrue(
                outcomes.get("TIGHT_EXTENSION_REVERSAL").compareTo(outcomes.get("BASE_REVERSAL"))
                        > 0);
        assertEquals(
                outcomes.get("BASE_CONTINUATION"), outcomes.get("STRONG_RELATIVE_CONTINUATION"));
        assertEquals(outcomes.get("BASE_REVERSAL"), outcomes.get("STRONG_RELATIVE_REVERSAL"));
    }

    @Test
    void candidateInputOrderDoesNotReplaceActualScoreOrdering() {
        ObjectNode forward = input(Variant.BASE, true);
        ObjectNode reverse = forward.deepCopy();
        for (JsonNode f : reverse.path("frames")) {
            ArrayNode rows = ((ObjectNode) f).withArray("rankings");
            List<JsonNode> copy = new ArrayList<>();
            rows.forEach(copy::add);
            Collections.reverse(copy);
            rows.removeAll();
            copy.forEach(rows::add);
        }
        ObjectNode a = run("PERMUTATION_FORWARD", "REGRESSION", "CONTINUATION", forward);
        ObjectNode b = run("PERMUTATION_REVERSE", "REGRESSION", "CONTINUATION", reverse);
        assertEquals(a.path("attempted_orders"), b.path("attempted_orders"));
        assertEquals(a.path("ledger"), b.path("ledger"));
        assertEquals("ALPHA", a.path("attempted_orders").get(0).path("symbol").asText());
    }

    @Test
    void tiedScoreAndBrokerRankUseStableSymbolOrder() {
        ObjectNode forward = input(Variant.BASE, true);
        for (JsonNode f : forward.path("frames")) {
            ObjectNode node = (ObjectNode) f;
            ObjectNode alphaRank = ((ObjectNode) node.path("rankings").get(0)).deepCopy();
            alphaRank.put("symbol", "BETA").put("name", "Synthetic BETA");
            node.withArray("rankings").set(1, alphaRank);
            for (String section : List.of("quotes", "completed_bars"))
                node.withObject("/" + section)
                        .set("BETA", node.path(section).path("ALPHA").deepCopy());
        }
        ObjectNode reversed = forward.deepCopy();
        for (JsonNode f : reversed.path("frames")) {
            ArrayNode ranks = (ArrayNode) f.path("rankings");
            JsonNode alpha = ranks.get(0);
            ranks.set(0, ranks.get(1));
            ranks.set(1, alpha);
        }
        ObjectNode a = run("TIE_FORWARD", "REGRESSION", "TIED_CANDIDATES", forward);
        ObjectNode b = run("TIE_REVERSED", "REGRESSION", "TIED_CANDIDATES", reversed);
        assertEquals("ALPHA", a.path("attempted_orders").get(0).path("symbol").asText());
        assertEquals("ALPHA", b.path("attempted_orders").get(0).path("symbol").asText());
        assertEquals(a.path("ledger"), b.path("ledger"));
    }

    @Test
    void DowntrendingBenchmarkRejectsAllCandidatesRatherThanInventingOpportunity() {
        ObjectNode data = input(Variant.BASE, true);
        for (JsonNode f : data.path("frames")) {
            ArrayNode bars = (ArrayNode) f.path("completed_bars").path("SPY");
            for (int i = 0; i < bars.size(); i++) {
                double p = 120 - i * .1;
                ((ObjectNode) bars.get(i))
                        .put("open", p)
                        .put("high", p + .5)
                        .put("low", p - .5)
                        .put("close", p);
            }
        }
        ObjectNode result = run("MARKET_DOWNTREND", "REGRESSION", "DOWNTREND", data);
        assertTrue(result.path("attempted_orders").isEmpty());
        assertEquals(0, equity(result).compareTo(new BigDecimal("10000")));
        assertEquals(
                "NO_CANDIDATE",
                result.path("trace").get(0).path("decision").path("status").asText());
    }

    @AfterAll
    static void report() throws Exception {
        String output = System.getProperty("us.selection.output");
        if (output == null) return;
        ObjectNode result =
                KiwoomUsProductionReplayHarness.JSON
                        .createObjectNode()
                        .put("schema_version", 1)
                        .put("dataset_kind", "synthetic")
                        .put("execution_mode", "OFFLINE_CAUSAL_ACTUAL_SERVICES")
                        .put("performance_verified", false)
                        .put("real_order_count", 0)
                        .put("strategy_adopted", false)
                        .put(
                                "experiment_design",
                                "BASE(0,1), TIGHT_EXTENSION(0,.25), STRONG_RELATIVE(8,1), values=(minRelativeStrengthPercent,maxEntryExtensionAtr). Two intentionally opposite outcome paths; no parameter search or empirical winner.")
                        .put(
                                "test_result_authority",
                                "Check Maven/JUnit assertions and independent ledger audit; report existence alone is insufficient.");
        result.putNull("return_pct").putNull("benchmark_return_pct");
        result.set("production_sha256", KiwoomUsProductionReplayHarness.productionHashes());
        result.set("scenarios", REPORTS);
        Path path = Path.of(output).toAbsolutePath().normalize();
        Files.createDirectories(path.getParent());
        Files.writeString(
                path,
                KiwoomUsProductionReplayHarness.JSON
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(result));
    }
}
