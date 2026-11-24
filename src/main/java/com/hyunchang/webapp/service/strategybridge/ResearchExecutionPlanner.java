package com.hyunchang.webapp.service.strategybridge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Whole-account order deltas. Sell proceeds never increase this batch's cash budget. */
public final class ResearchExecutionPlanner {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final BigDecimal FEE_FACTOR = new BigDecimal("1.00125");

    private ResearchExecutionPlanner() {}

    public record Position(String exchange, int quantity, int sellableQuantity) {
        public Position {
            if (exchange == null
                    || exchange.isBlank()
                    || quantity < 0
                    || sellableQuantity < 0
                    || sellableQuantity > quantity)
                throw new IllegalArgumentException("Invalid current holding");
        }
    }

    public record Quote(BigDecimal bid, BigDecimal ask) {
        public Quote {
            if (bid == null || ask == null || bid.signum() <= 0 || ask.compareTo(bid) < 0)
                throw new IllegalArgumentException("Invalid current quote");
        }
    }

    public record Order(
            String side, String exchange, String symbol, int quantity, BigDecimal price) {}

    public record Plan(
            List<Order> orders, String mode, String nextStateJson, BigDecimal buyCostCap) {}

    public record SavedState(
            String version,
            String signalDate,
            List<String> basket,
            Double anchor,
            String deploymentId,
            Map<String, Integer> fundShortfall,
            Map<String, Double> fundWeights,
            double fundGross) {}

    public static Plan plan(
            UsResearchDeploymentGateway.ExecutionContext context,
            BigDecimal equity,
            BigDecimal peak,
            BigDecimal cash,
            Map<String, Position> positions,
            Map<String, Quote> quotes) {
        if (equity == null
                || peak == null
                || cash == null
                || equity.signum() <= 0
                || peak.compareTo(equity) < 0
                || cash.signum() < 0)
            throw new IllegalArgumentException("Invalid real account funding");
        SavedState before = read(context.stateJson());
        boolean monthly =
                before == null
                        || !context.version().equals(before.version())
                        || !context.deploymentId().equals(before.deploymentId())
                        || !context.signalDate()
                                .toString()
                                .substring(0, 7)
                                .equals(before.signalDate().substring(0, 7));
        if (before != null && !context.signalDate().isAfter(LocalDate.parse(before.signalDate())))
            throw new IllegalStateException("이미 처리한 완료 일봉입니다.");
        var decision =
                ResearchStrategyEngine.decide(
                        context.candidateId(),
                        before == null ? List.of() : before.basket(),
                        context.signals(),
                        monthly,
                        equity.doubleValue(),
                        peak.doubleValue(),
                        false,
                        before == null ? null : before.anchor());
        String mode = decision.mode();
        Map<String, Double> weights = decision.weights();
        if (mode.equals("HOLD") && before != null && !before.fundShortfall().isEmpty()) {
            mode = "FUND";
            double factor =
                    before.fundGross() > 0 ? Math.min(1, decision.gross() / before.fundGross()) : 0;
            Map<String, Double> adjusted = new LinkedHashMap<>();
            before.fundWeights().forEach((s, w) -> adjusted.put(s, w * factor));
            weights = adjusted;
        }
        TreeSet<String> symbols = new TreeSet<>(ResearchStrategyEngine.SYMBOLS);
        symbols.addAll(positions.keySet());
        Map<String, Integer> desired = new LinkedHashMap<>();
        for (String symbol : symbols) {
            int held = positions.containsKey(symbol) ? positions.get(symbol).quantity() : 0;
            int target = held;
            // Existing non-catalog stocks are in management scope; no implicit exclusion.
            if (!ResearchStrategyEngine.SYMBOLS.contains(symbol)) target = 0;
            else if (mode.equals("EXIT")) target = decision.basket().contains(symbol) ? held : 0;
            else if (!mode.equals("HOLD")) {
                Quote quote = requireQuote(quotes, symbol);
                target =
                        BigDecimal.valueOf(weights.getOrDefault(symbol, 0.))
                                .multiply(equity)
                                .divide(quote.ask().multiply(FEE_FACTOR), 0, RoundingMode.DOWN)
                                .intValueExact();
                if (mode.equals("REDUCE")) target = Math.min(held, target);
                else if (mode.equals("FUND"))
                    target =
                            held
                                    + Math.min(
                                            before.fundShortfall().getOrDefault(symbol, 0),
                                            Math.max(0, target - held));
            }
            desired.put(symbol, target);
        }
        List<Order> orders = new ArrayList<>();
        for (String symbol : symbols) {
            Position held = positions.get(symbol);
            int sell = held == null ? 0 : Math.max(0, held.quantity() - desired.get(symbol));
            if (sell > 0) {
                if (sell > held.sellableQuantity())
                    throw new IllegalStateException("매도 가능 수량 확인 필요: " + symbol);
                orders.add(
                        new Order(
                                "SELL",
                                held.exchange(),
                                symbol,
                                sell,
                                requireQuote(quotes, symbol).bid()));
            }
        }
        BigDecimal budget = cash.multiply(new BigDecimal("0.99"));
        BigDecimal initialBudget = budget;
        Map<String, Integer> shortfall = new LinkedHashMap<>();
        if (mode.equals("FULL") || mode.equals("FUND")) {
            for (String symbol : symbols) {
                int held = positions.containsKey(symbol) ? positions.get(symbol).quantity() : 0;
                int missing = Math.max(0, desired.get(symbol) - held);
                if (missing == 0) continue;
                Quote quote = requireQuote(quotes, symbol);
                BigDecimal unit = quote.ask().multiply(FEE_FACTOR);
                int buy =
                        Math.min(
                                missing, budget.divide(unit, 0, RoundingMode.DOWN).intValueExact());
                if (buy > 0) {
                    String exchange =
                            positions.containsKey(symbol)
                                    ? positions.get(symbol).exchange()
                                    : exchange(symbol);
                    orders.add(new Order("BUY", exchange, symbol, buy, quote.ask()));
                    budget = budget.subtract(unit.multiply(BigDecimal.valueOf(buy)));
                }
                if (mode.equals("FULL") && missing > buy) shortfall.put(symbol, missing - buy);
            }
        }
        Double anchor =
                mode.equals("FULL") || mode.equals("REDUCE")
                        ? decision.gross()
                        : before == null ? null : before.anchor();
        SavedState after =
                new SavedState(
                        context.version(),
                        context.signalDate().toString(),
                        decision.basket(),
                        anchor,
                        context.deploymentId(),
                        shortfall,
                        shortfall.isEmpty() ? Map.of() : weights,
                        shortfall.isEmpty() ? 0 : decision.gross());
        try {
            return new Plan(
                    List.copyOf(orders),
                    mode,
                    JSON.writeValueAsString(after),
                    initialBudget.subtract(budget));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("전략 상태 저장 준비 실패", error);
        }
    }

    public static String exchange(String symbol) {
        if (!ResearchStrategyEngine.SYMBOLS.contains(symbol))
            throw new IllegalArgumentException("Unregistered buy symbol");
        return symbol.equals("SPY") || symbol.equals("GLD") ? "NA" : "ND";
    }

    private static Quote requireQuote(Map<String, Quote> quotes, String symbol) {
        Quote quote = quotes.get(symbol);
        if (quote == null) throw new IllegalArgumentException("Fresh quote required: " + symbol);
        return quote;
    }

    private static SavedState read(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            SavedState state = JSON.readValue(text, SavedState.class);
            LocalDate.parse(state.signalDate());
            if (state.version() == null
                    || state.basket() == null
                    || state.fundShortfall() == null
                    || state.fundWeights() == null)
                throw new IllegalArgumentException("Missing durable strategy state");
            for (var item : state.fundShortfall().entrySet())
                if (!ResearchStrategyEngine.SYMBOLS.contains(item.getKey())
                        || item.getValue() == null
                        || item.getValue() < 0)
                    throw new IllegalArgumentException("Invalid funding completion state");
            for (var item : state.fundWeights().entrySet())
                if (!ResearchStrategyEngine.SYMBOLS.contains(item.getKey())
                        || item.getValue() == null
                        || !Double.isFinite(item.getValue())
                        || item.getValue() < 0
                        || item.getValue() > .8)
                    throw new IllegalArgumentException("Invalid funding weights");
            if (!Double.isFinite(state.fundGross())
                    || state.fundGross() < 0
                    || state.fundGross() > .8)
                throw new IllegalArgumentException("Invalid funding gross");
            return state;
        } catch (RuntimeException | JsonProcessingException error) {
            throw new IllegalStateException("저장된 연구 전략 상태 확인 필요", error);
        }
    }
}
