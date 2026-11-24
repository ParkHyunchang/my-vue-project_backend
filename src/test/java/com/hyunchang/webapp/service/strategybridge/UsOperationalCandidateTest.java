package com.hyunchang.webapp.service.strategybridge;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Synthetic suitability scenarios; no broker, credentials, or real account valuation. */
class UsOperationalCandidateTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, BigDecimal> PRICES =
            Map.of(
                    "SPY",
                    new BigDecimal("600"),
                    "QQQ",
                    new BigDecimal("500"),
                    "GLD",
                    new BigDecimal("250"),
                    "IEF",
                    new BigDecimal("100"),
                    "JEPQ",
                    new BigDecimal("60"));

    private Map<String, ResearchExecutionPlanner.Quote> quotes() {
        Map<String, ResearchExecutionPlanner.Quote> result = new LinkedHashMap<>();
        PRICES.forEach(
                (symbol, price) ->
                        result.put(symbol, new ResearchExecutionPlanner.Quote(price, price)));
        return result;
    }

    private UsResearchDeploymentGateway.ExecutionContext context(String date, String state) {
        Map<String, ResearchStrategyEngine.Signal> signals = new LinkedHashMap<>();
        ResearchStrategyEngine.SYMBOLS.forEach(
                s -> signals.put(s, new ResearchStrategyEngine.Signal(true, .1, .1, .1, 1)));
        return new UsResearchDeploymentGateway.ExecutionContext(
                "synthetic-deployment",
                "balanced_trend:recovery_vol",
                "synthetic-version",
                LocalDate.parse(date),
                Instant.parse("2026-11-21T00:00:00Z"),
                signals,
                state,
                "synthetic-hash");
    }

    @Test
    void integerShareSizingRevealsSmallAccountConcentrationAndNeverSpendsSaleProceeds()
            throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (double amount : new double[] {0, 35.48, 100, 500, 1000, 5000, 10000}) {
            BigDecimal cash = BigDecimal.valueOf(amount);
            BigDecimal equity = cash.add(new BigDecimal("180"));
            var plan =
                    ResearchExecutionPlanner.plan(
                            context("2026-11-20", null),
                            equity,
                            equity,
                            cash,
                            Map.of("JEPQ", new ResearchExecutionPlanner.Position("ND", 3, 3)),
                            quotes());
            assertEquals("SELL", plan.orders().getFirst().side());
            assertEquals("JEPQ", plan.orders().getFirst().symbol());
            assertEquals(3, plan.orders().getFirst().quantity());
            BigDecimal notional =
                    plan.orders().stream()
                            .filter(o -> o.side().equals("BUY"))
                            .map(o -> o.price().multiply(BigDecimal.valueOf(o.quantity())))
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertTrue(
                    notional.multiply(new BigDecimal("1.00125"))
                                    .compareTo(cash.multiply(new BigDecimal(".99")))
                            <= 0);
            if (amount <= 1000) {
                assertTrue(
                        plan.orders().stream()
                                .noneMatch(
                                        o ->
                                                o.side().equals("BUY")
                                                        && (o.symbol().equals("SPY")
                                                                || o.symbol().equals("QQQ"))));
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("cashUsd", cash);
            row.put("syntheticEquityUsd", equity);
            row.put("orders", plan.orders());
            row.put("buyNotionalUsd", notional);
            row.put("buyCostsIncludingAssumedFeeUsd", plan.buyCostCap());
            row.put(
                    "postSaleBuyExposurePercent",
                    100 * notional.doubleValue() / equity.doubleValue());
            rows.add(row);
        }
        Path directory = Path.of("tools", "team31_results");
        Files.createDirectories(directory);
        JSON.writerWithDefaultPrettyPrinter()
                .writeValue(
                        directory.resolve("execution-sizing.json").toFile(),
                        Map.of(
                                "candidate",
                                "balanced_trend:recovery_vol",
                                "syntheticOnly",
                                true,
                                "quotesAreNotMarketData",
                                true,
                                "assumedZeroSpreadPricesUsd",
                                PRICES,
                                "assumedBuyFeeBps",
                                12.5,
                                "initialHoldings",
                                "JEPQ 3 shares; synthetic value USD180",
                                "scenarios",
                                rows));
    }

    @Test
    void cashArrivingAfterTheSingleFundingDayLeavesAllocationDeferredUntilRebalance()
            throws Exception {
        BigDecimal equity = new BigDecimal("6000");
        var initial =
                ResearchExecutionPlanner.plan(
                        context("2026-11-20", null),
                        equity,
                        equity,
                        BigDecimal.ZERO,
                        Map.of("SPY", new ResearchExecutionPlanner.Position("NA", 10, 10)),
                        quotes());
        assertTrue(initial.orders().stream().allMatch(o -> o.side().equals("SELL")));
        assertFalse(JSON.readTree(initial.nextStateJson()).path("fundShortfall").isEmpty());
        // Hypothetical delayed sale proceeds: confirmed quantity changed, but buying cash
        // unavailable.
        var holdings = Map.of("SPY", new ResearchExecutionPlanner.Position("NA", 3, 3));
        var waiting =
                ResearchExecutionPlanner.plan(
                        context("2026-11-23", initial.nextStateJson()),
                        equity,
                        equity,
                        BigDecimal.ZERO,
                        holdings,
                        quotes());
        assertEquals("FUND", waiting.mode());
        assertTrue(waiting.orders().isEmpty());
        var later =
                ResearchExecutionPlanner.plan(
                        context("2026-11-24", waiting.nextStateJson()),
                        equity,
                        equity,
                        new BigDecimal("4200"),
                        holdings,
                        quotes());
        assertEquals("HOLD", later.mode());
        assertTrue(
                later.orders().isEmpty(),
                "Delayed proceeds do not cause an automatic funding retry");
        assertThrows(
                IllegalStateException.class,
                () ->
                        ResearchExecutionPlanner.plan(
                                context("2026-11-24", later.nextStateJson()),
                                equity,
                                equity,
                                new BigDecimal("4200"),
                                holdings,
                                quotes()));
        var rebalance =
                ResearchExecutionPlanner.plan(
                        context("2026-12-01", later.nextStateJson()),
                        equity,
                        equity,
                        new BigDecimal("4200"),
                        holdings,
                        quotes());
        assertTrue(rebalance.orders().stream().anyMatch(o -> o.side().equals("BUY")));
    }
}
