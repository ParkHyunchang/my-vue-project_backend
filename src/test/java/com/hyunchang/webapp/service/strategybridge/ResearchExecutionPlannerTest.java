package com.hyunchang.webapp.service.strategybridge;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResearchExecutionPlannerTest {
    private final Map<String, ResearchStrategyEngine.Signal> signals = new LinkedHashMap<>();
    private final Map<String, ResearchExecutionPlanner.Quote> quotes = new LinkedHashMap<>();

    ResearchExecutionPlannerTest() {
        int rank = 0;
        for (String symbol : ResearchStrategyEngine.SYMBOLS) {
            signals.put(symbol, new ResearchStrategyEngine.Signal(true, ++rank * .1, .1, .1, rank));
            quotes.put(
                    symbol,
                    new ResearchExecutionPlanner.Quote(
                            new BigDecimal("100"), new BigDecimal("100")));
        }
        quotes.put(
                "OLD",
                new ResearchExecutionPlanner.Quote(new BigDecimal("100"), new BigDecimal("100")));
    }

    private UsResearchDeploymentGateway.ExecutionContext context(
            String version, String date, String saved) {
        return new UsResearchDeploymentGateway.ExecutionContext(
                "deployment",
                "balanced_trend:recovery_vol",
                version,
                LocalDate.parse(date),
                Instant.now(),
                signals,
                saved,
                "market-hash");
    }

    @Test
    void preexistingPositionsAreSoldButProceedsCannotFinanceSameBatchBuys() throws Exception {
        var plan =
                ResearchExecutionPlanner.plan(
                        context("v1", "2026-11-20", null),
                        new BigDecimal("2000"),
                        new BigDecimal("2000"),
                        new BigDecimal("1000"),
                        Map.of("OLD", new ResearchExecutionPlanner.Position("NASDAQ", 10, 10)),
                        quotes);
        assertEquals("SELL", plan.orders().getFirst().side());
        assertEquals("OLD", plan.orders().getFirst().symbol());
        assertEquals(10, plan.orders().getFirst().quantity());
        assertTrue(plan.buyCostCap().compareTo(new BigDecimal("990")) <= 0);
        assertTrue(
                plan.orders().stream()
                                .filter(o -> o.side().equals("BUY"))
                                .mapToInt(ResearchExecutionPlanner.Order::quantity)
                                .sum()
                        <= 9);
        var saved = new ObjectMapper().readTree(plan.nextStateJson());
        assertTrue(saved.path("fundShortfall").size() > 0);
    }

    @Test
    void supportsAddingToExistingApprovedEtfAndPreservesWholeAccountSizing() {
        var plan =
                ResearchExecutionPlanner.plan(
                        context("v1", "2026-11-20", null),
                        new BigDecimal("1000"),
                        new BigDecimal("1000"),
                        new BigDecimal("900"),
                        Map.of("SPY", new ResearchExecutionPlanner.Position("AMEX", 1, 1)),
                        quotes);
        assertEquals(
                2,
                plan.orders().stream()
                        .filter(o -> o.symbol().equals("SPY") && o.side().equals("BUY"))
                        .findFirst()
                        .orElseThrow()
                        .quantity());
        assertTrue(plan.buyCostCap().compareTo(new BigDecimal("891")) <= 0);
    }

    @Test
    void soldCashIsNeverAssumedAvailableAndUnsellableHoldingsBlockBeforeClaim() {
        var zeroCash =
                ResearchExecutionPlanner.plan(
                        context("v1", "2026-11-20", null),
                        new BigDecimal("1000"),
                        new BigDecimal("1000"),
                        BigDecimal.ZERO,
                        Map.of("OLD", new ResearchExecutionPlanner.Position("NASDAQ", 10, 10)),
                        quotes);
        assertTrue(zeroCash.orders().stream().noneMatch(o -> o.side().equals("BUY")));
        assertThrows(
                IllegalStateException.class,
                () ->
                        ResearchExecutionPlanner.plan(
                                context("v1", "2026-11-20", null),
                                new BigDecimal("1000"),
                                new BigDecimal("1000"),
                                BigDecimal.ZERO,
                                Map.of(
                                        "OLD",
                                        new ResearchExecutionPlanner.Position("NASDAQ", 10, 9)),
                                quotes));
    }

    @Test
    void duplicateDayCannotReplayAndStrategySwitchChangesFutureDecisionOnly() throws Exception {
        var first =
                ResearchExecutionPlanner.plan(
                        context("v1", "2026-11-19", null),
                        new BigDecimal("1000"),
                        new BigDecimal("1000"),
                        new BigDecimal("1000"),
                        Map.of(),
                        quotes);
        assertThrows(
                IllegalStateException.class,
                () ->
                        ResearchExecutionPlanner.plan(
                                context("v2", "2026-11-19", first.nextStateJson()),
                                new BigDecimal("1000"),
                                new BigDecimal("1000"),
                                new BigDecimal("1000"),
                                Map.of(),
                                quotes));
        var next =
                ResearchExecutionPlanner.plan(
                        context("v2", "2026-11-20", first.nextStateJson()),
                        new BigDecimal("920"),
                        new BigDecimal("1000"),
                        BigDecimal.ZERO,
                        Map.of("SPY", new ResearchExecutionPlanner.Position("AMEX", 9, 9)),
                        quotes);
        assertEquals("FULL", next.mode());
        assertTrue(next.orders().stream().noneMatch(o -> o.side().equals("BUY")));
        assertEquals(
                .2,
                new ObjectMapper().readTree(next.nextStateJson()).path("anchor").asDouble(),
                1e-12);
    }

    @Test
    void ordinaryDayHoldsAndOneFundingCompletionIsCapped() throws Exception {
        var state =
                new ResearchExecutionPlanner.SavedState(
                        "v1",
                        "2026-11-19",
                        List.of("SPY", "QQQ", "GLD", "IEF"),
                        .8,
                        "deployment",
                        Map.of("SPY", 2),
                        Map.of("SPY", .32),
                        .8);
        String json = new ObjectMapper().writeValueAsString(state);
        var plan =
                ResearchExecutionPlanner.plan(
                        context("v1", "2026-11-20", json),
                        new BigDecimal("2000"),
                        new BigDecimal("2000"),
                        new BigDecimal("2000"),
                        Map.of(),
                        quotes);
        assertEquals("FUND", plan.mode());
        assertEquals(
                2,
                plan.orders().stream()
                        .filter(o -> o.side().equals("BUY"))
                        .mapToInt(ResearchExecutionPlanner.Order::quantity)
                        .sum());
        assertTrue(
                new ObjectMapper().readTree(plan.nextStateJson()).path("fundShortfall").isEmpty());
    }
}
