package com.hyunchang.webapp.service.strategybridge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure TEAM-24 completed-close decision rules. Never reads accounts or sends orders. */
public final class ResearchStrategyEngine {
    public static final List<String> SYMBOLS = List.of("SPY", "QQQ", "GLD", "IEF");
    public static final Set<String> CANDIDATES = Set.of("rotation1", "riskrank2", "balanced_trend");
    public static final Set<String> POLICIES = Set.of("cushion80", "recovery_vol");
    private static final Map<String, Double> BASE =
            Map.of("SPY", .4, "QQQ", .2, "GLD", .2, "IEF", .2);

    private ResearchStrategyEngine() {}

    public record Signal(
            boolean eligible, double momentum, double vol20, double vol63, double riskrank) {
        public Signal {
            if (!Double.isFinite(momentum)
                    || !Double.isFinite(vol20)
                    || !Double.isFinite(vol63)
                    || !Double.isFinite(riskrank)
                    || vol20 < 0
                    || vol63 < 0) throw new IllegalArgumentException("Invalid completed signal");
        }
    }

    public record Decision(
            List<String> basket, double gross, Map<String, Double> weights, String mode) {
        public Decision {
            basket = List.copyOf(basket);
            weights = Map.copyOf(weights);
        }
    }

    public static Decision decide(
            String candidateId,
            List<String> previousBasket,
            Map<String, Signal> signals,
            boolean monthly,
            double equity,
            double peak,
            boolean hard,
            Double previousAnchor) {
        String[] selection = candidateId == null ? new String[0] : candidateId.split(":", -1);
        if (selection.length != 2
                || !CANDIDATES.contains(selection[0])
                || !POLICIES.contains(selection[1]))
            throw new IllegalArgumentException("Unknown research strategy");
        if (signals == null || !signals.keySet().equals(Set.copyOf(SYMBOLS)))
            throw new IllegalArgumentException("All four completed ETF signals are required");
        for (String symbol : SYMBOLS)
            if (signals.get(symbol) == null)
                throw new IllegalArgumentException("Missing completed signal");
        if (previousBasket == null
                || !SYMBOLS.containsAll(previousBasket)
                || Set.copyOf(previousBasket).size() != previousBasket.size())
            throw new IllegalArgumentException("Invalid previous basket");
        if (previousAnchor != null
                && (!Double.isFinite(previousAnchor) || previousAnchor < 0 || previousAnchor > .8))
            throw new IllegalArgumentException("Invalid rebalance anchor");
        String candidate = selection[0];
        List<String> basket = new ArrayList<>();
        for (String symbol : monthly ? SYMBOLS : previousBasket) {
            Signal signal = signals.get(symbol);
            if (signal.eligible() && (candidate.equals("balanced_trend") || signal.momentum() > 0))
                basket.add(symbol);
        }
        if (monthly && !candidate.equals("balanced_trend")) {
            basket.sort(
                    Comparator.<String>comparingDouble(
                                    s ->
                                            -(candidate.equals("rotation1")
                                                    ? signals.get(s).momentum()
                                                    : signals.get(s).riskrank()))
                            .thenComparing(Comparator.naturalOrder()));
            basket =
                    new ArrayList<>(
                            basket.subList(
                                    0,
                                    Math.min(
                                            basket.size(), candidate.equals("rotation1") ? 1 : 2)));
        }
        double gross = riskTarget(equity, peak, hard, selection[1], signals.get("SPY").vol20());
        Map<String, Double> weights = new LinkedHashMap<>();
        for (String symbol : basket)
            weights.put(
                    symbol,
                    candidate.equals("balanced_trend")
                            ? gross * BASE.get(symbol)
                            : gross / (candidate.equals("rotation1") ? 1 : 2));
        String mode = "HOLD";
        if (!hard) {
            if (monthly) mode = "FULL";
            else if (previousAnchor != null && gross <= previousAnchor - .05 + 1e-12)
                mode = "REDUCE";
            else if (!basket.containsAll(previousBasket)) mode = "EXIT";
        }
        return new Decision(basket, gross, weights, mode);
    }

    public static double riskTarget(
            double equity, double peak, boolean hard, String policy, double volatility) {
        if (!POLICIES.contains(policy)
                || !Double.isFinite(equity)
                || !Double.isFinite(peak)
                || equity <= 0
                || peak < equity
                || !Double.isFinite(volatility)
                || volatility < 0)
            throw new IllegalArgumentException("Invalid research risk inputs");
        if (hard) return 0;
        if (policy.equals("cushion80"))
            return Math.min(.8, 8 * Math.max(0, equity - .9 * peak) / equity);
        double gross = Math.min(.8, Math.max(.2, .10 / Math.max(.05, volatility)));
        if (equity <= .92 * peak) return Math.min(gross, .2);
        if (equity <= .95 * peak) return Math.min(gross, .4);
        return gross;
    }
}
