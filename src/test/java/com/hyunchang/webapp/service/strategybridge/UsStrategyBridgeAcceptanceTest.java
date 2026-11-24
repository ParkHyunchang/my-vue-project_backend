package com.hyunchang.webapp.service.strategybridge;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Independent boundary checks; no Spring boot, broker, database, or network. */
class UsStrategyBridgeAcceptanceTest {
    private UsResearchDeploymentGateway.ExecutionContext context(String date, String state) {
        return new UsResearchDeploymentGateway.ExecutionContext(
                "deployment-test",
                "rotation1:recovery_vol",
                "version-test",
                LocalDate.parse(date),
                Instant.parse(date + "T22:00:00Z"),
                signals(),
                state,
                "market-test");
    }

    private Map<String, ResearchExecutionPlanner.Quote> quotes() {
        Map<String, ResearchExecutionPlanner.Quote> result = new LinkedHashMap<>();
        for (String symbol : List.of("SPY", "QQQ", "GLD", "IEF", "AAPL"))
            result.put(
                    symbol,
                    new ResearchExecutionPlanner.Quote(
                            new BigDecimal("99"), new BigDecimal("100")));
        return result;
    }

    private Map<String, ResearchStrategyEngine.Signal> signals() {
        Map<String, ResearchStrategyEngine.Signal> result = new LinkedHashMap<>();
        for (String symbol : ResearchStrategyEngine.SYMBOLS)
            result.put(symbol, new ResearchStrategyEngine.Signal(true, .10, .10, .20, .50));
        return result;
    }

    @Test
    void monthlyRankingHasDeterministicTiesAndRetainsUnallocatedCash() {
        var inputs = signals();
        var one =
                ResearchStrategyEngine.decide(
                        "rotation1:recovery_vol",
                        List.of(),
                        inputs,
                        true,
                        10000,
                        10000,
                        false,
                        null);
        assertEquals(List.of("GLD"), one.basket());
        assertEquals(.8, one.weights().get("GLD"), 1e-12);
        inputs.replaceAll(
                (symbol, signal) ->
                        new ResearchStrategyEngine.Signal(
                                symbol.equals("QQQ"), .10, .10, .20, .50));
        var two =
                ResearchStrategyEngine.decide(
                        "riskrank2:recovery_vol",
                        List.of(),
                        inputs,
                        true,
                        10000,
                        10000,
                        false,
                        null);
        assertEquals(List.of("QQQ"), two.basket());
        assertEquals(.4, two.weights().get("QQQ"), 1e-12);
        assertEquals("FULL", two.mode());
    }

    @Test
    void dailyDecisionDoesNotAddNewWinnersAndExitsLostTrend() {
        var inputs = signals();
        var held =
                ResearchStrategyEngine.decide(
                        "rotation1:recovery_vol",
                        List.of("SPY"),
                        inputs,
                        false,
                        10000,
                        10000,
                        false,
                        .8);
        assertEquals(List.of("SPY"), held.basket());
        assertEquals("HOLD", held.mode());
        inputs.put("SPY", new ResearchStrategyEngine.Signal(false, .10, .10, .20, .50));
        var lost =
                ResearchStrategyEngine.decide(
                        "rotation1:recovery_vol",
                        List.of("SPY"),
                        inputs,
                        false,
                        10000,
                        10000,
                        false,
                        .8);
        assertTrue(lost.basket().isEmpty());
        assertEquals("EXIT", lost.mode());
    }

    @Test
    void balancedTrendUsesFixedAssetWeightsWithoutRedistributingMissingAssets() {
        var inputs = signals();
        inputs.put("SPY", new ResearchStrategyEngine.Signal(false, -.1, .10, .20, -.5));
        inputs.put("QQQ", new ResearchStrategyEngine.Signal(false, -.1, .10, .20, -.5));
        inputs.put("GLD", new ResearchStrategyEngine.Signal(true, -.1, .10, .20, -.5));
        inputs.put("IEF", new ResearchStrategyEngine.Signal(false, -.1, .10, .20, -.5));
        var result =
                ResearchStrategyEngine.decide(
                        "balanced_trend:recovery_vol",
                        List.of(),
                        inputs,
                        true,
                        10000,
                        10000,
                        false,
                        null);
        assertEquals(List.of("GLD"), result.basket());
        assertEquals(.16, result.weights().get("GLD"), 1e-12);
    }

    @Test
    void lossBoundariesAndPermanentLatchCannotIncreaseRisk() {
        assertEquals(
                .8,
                ResearchStrategyEngine.riskTarget(10000, 10000, false, "recovery_vol", .01),
                1e-12);
        assertEquals(
                .4,
                ResearchStrategyEngine.riskTarget(9500, 10000, false, "recovery_vol", .01),
                1e-12);
        assertEquals(
                .2,
                ResearchStrategyEngine.riskTarget(9200, 10000, false, "recovery_vol", .01),
                1e-12);
        for (String policy : ResearchStrategyEngine.POLICIES) {
            assertEquals(0, ResearchStrategyEngine.riskTarget(10000, 10000, true, policy, .01));
            var result =
                    ResearchStrategyEngine.decide(
                            "rotation1:" + policy,
                            List.of("SPY"),
                            signals(),
                            true,
                            10000,
                            10000,
                            true,
                            .8);
            assertEquals(0, result.gross());
            assertEquals("HOLD", result.mode());
            assertTrue(result.weights().values().stream().allMatch(w -> w == 0));
        }
    }

    @Test
    void malformedSignalsAndUnregisteredStrategiesFailClosed() {
        var incomplete = signals();
        incomplete.remove("IEF");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ResearchStrategyEngine.decide(
                                "rotation1:recovery_vol",
                                List.of(),
                                incomplete,
                                true,
                                10000,
                                10000,
                                false,
                                null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ResearchStrategyEngine.decide(
                                "freeform:recovery_vol",
                                List.of(),
                                signals(),
                                true,
                                10000,
                                10000,
                                false,
                                null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ResearchStrategyEngine.decide(
                                "rotation1:recovery_vol",
                                List.of("AAPL"),
                                signals(),
                                true,
                                10000,
                                10000,
                                false,
                                null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ResearchStrategyEngine.decide(
                                "rotation1:recovery_vol",
                                List.of("SPY", "SPY"),
                                signals(),
                                true,
                                10000,
                                10000,
                                false,
                                null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ResearchStrategyEngine.Signal(true, Double.NaN, .1, .1, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> ResearchStrategyEngine.riskTarget(10001, 10000, false, "cushion80", .1));
    }

    @Test
    void existingOutOfCatalogHoldingIsSoldButCannotFundSameBatchBuys() {
        var positions = Map.of("AAPL", new ResearchExecutionPlanner.Position("NASDAQ", 100, 100));
        var result =
                ResearchExecutionPlanner.plan(
                        context("2026-11-20", null),
                        new BigDecimal("10000"),
                        new BigDecimal("10000"),
                        BigDecimal.ZERO,
                        positions,
                        quotes());
        assertEquals(1, result.orders().size());
        assertEquals("SELL", result.orders().getFirst().side());
        assertEquals("AAPL", result.orders().getFirst().symbol());
        assertEquals(100, result.orders().getFirst().quantity());
        assertEquals(0, result.buyCostCap().signum());
    }

    @Test
    void existingWinningHoldingCanBeToppedUpWithinFeeReservedCash() {
        var positions = Map.of("GLD", new ResearchExecutionPlanner.Position("AMEX", 1, 1));
        var result =
                ResearchExecutionPlanner.plan(
                        context("2026-11-20", null),
                        new BigDecimal("10000"),
                        new BigDecimal("10000"),
                        new BigDecimal("1000"),
                        positions,
                        quotes());
        assertEquals(1, result.orders().size());
        assertEquals("BUY", result.orders().getFirst().side());
        assertEquals("GLD", result.orders().getFirst().symbol());
        assertEquals(9, result.orders().getFirst().quantity());
        assertTrue(result.buyCostCap().compareTo(new BigDecimal("990")) <= 0);
    }

    @Test
    void fundingCompletionDoesNotRepeatAndCannotUseTheSameSignalTwice() {
        var first =
                ResearchExecutionPlanner.plan(
                        context("2026-11-20", null),
                        new BigDecimal("10000"),
                        new BigDecimal("10000"),
                        BigDecimal.ZERO,
                        Map.of(),
                        quotes());
        assertThrows(
                IllegalStateException.class,
                () ->
                        ResearchExecutionPlanner.plan(
                                context("2026-11-20", first.nextStateJson()),
                                new BigDecimal("10000"),
                                new BigDecimal("10000"),
                                new BigDecimal("10000"),
                                Map.of(),
                                quotes()));
        var next =
                ResearchExecutionPlanner.plan(
                        context("2026-11-23", first.nextStateJson()),
                        new BigDecimal("10000"),
                        new BigDecimal("10000"),
                        new BigDecimal("10000"),
                        Map.of(),
                        quotes());
        assertEquals("FUND", next.mode());
        assertEquals(79, next.orders().getFirst().quantity());
        var last =
                ResearchExecutionPlanner.plan(
                        context("2026-11-24", next.nextStateJson()),
                        new BigDecimal("10000"),
                        new BigDecimal("10000"),
                        new BigDecimal("10000"),
                        Map.of(),
                        quotes());
        assertEquals("HOLD", last.mode());
        assertTrue(last.orders().isEmpty());
    }

    @Test
    void insufficientSellableSharesAndMissingQuoteBlockWholePlan() {
        var held = Map.of("AAPL", new ResearchExecutionPlanner.Position("NASDAQ", 100, 20));
        assertThrows(
                IllegalStateException.class,
                () ->
                        ResearchExecutionPlanner.plan(
                                context("2026-11-20", null),
                                new BigDecimal("10000"),
                                new BigDecimal("10000"),
                                BigDecimal.ZERO,
                                held,
                                quotes()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ResearchExecutionPlanner.plan(
                                context("2026-11-20", null),
                                new BigDecimal("10000"),
                                new BigDecimal("10000"),
                                new BigDecimal("10000"),
                                Map.of(),
                                Map.of()));
    }
}
