package com.hyunchang.webapp.service;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.entity.KiwoomUsStrategySettings;
import com.hyunchang.webapp.service.KiwoomUsTradeService.DailyBar;
import com.hyunchang.webapp.service.KiwoomUsTradeService.OrderBookQuote;
import com.hyunchang.webapp.util.KiwoomUsMarketHours;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Offline only: exact production signal, historical data calendar, no application or API. */
class KiwoomUsReplayAdapterTest {
    @Test
    void syntheticAdapterUsesProductionSignalAndOnlyCompletedBars() {
        List<DailyBar> stock = new ArrayList<>(), spy = new ArrayList<>(), qqq = new ArrayList<>();
        NavigableSet<LocalDate> calendar = new TreeSet<>();
        LocalDate day = LocalDate.of(2018, 1, 2);
        for (int i = 0; i < 61; i++) {
            while (day.getDayOfWeek().getValue() > 5) day = day.plusDays(1);
            calendar.add(day);
            double close = 100 + i * .5, spyClose = 100 + i * .1, qqqClose = 100 + i * .15;
            stock.add(new DailyBar(day, close, close + .5, close - .5, close, 1000));
            spy.add(new DailyBar(day, spyClose, spyClose + .5, spyClose - .5, spyClose, 1000));
            qqq.add(new DailyBar(day, qqqClose, qqqClose + .5, qqqClose - .5, qqqClose, 1000));
            day = day.plusDays(1);
        }
        LocalDate session = day;
        try (var clock = mockStatic(KiwoomUsMarketHours.class)) {
            clock.when(() -> KiwoomUsMarketHours.previousTradingDay(any(LocalDate.class)))
                    .thenAnswer(call -> calendar.lower(call.getArgument(0)));
            var signal =
                    KiwoomUsTechnicalSignalService.calculate(
                            stock,
                            spy,
                            qqq,
                            new OrderBookQuote(new BigDecimal("130.5"), new BigDecimal("130.5")),
                            new KiwoomUsStrategySettings(),
                            session);
            org.junit.jupiter.api.Assertions.assertTrue(signal.accepted());
            org.junit.jupiter.api.Assertions.assertEquals(
                    stock.getLast().date(), signal.dataDate());
            stock.add(new DailyBar(session, 131, 132, 130, 131, 1000));
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class,
                    () ->
                            KiwoomUsTechnicalSignalService.calculate(
                                    stock,
                                    spy,
                                    qqq,
                                    new OrderBookQuote(
                                            new BigDecimal("131"), new BigDecimal("131")),
                                    new KiwoomUsStrategySettings(),
                                    session));
        }
    }

    @Test
    void exportProductionSignalsWithoutFutureBars() throws Exception {
        String input = System.getProperty("replay.input");
        assumeTrue(input != null, "Explicit replay.input is required for historical export");
        Path source = Path.of(input).toAbsolutePath().normalize();
        Path output =
                Path.of(System.getProperty("replay.output", "tools/market_data/signals.json"))
                        .toAbsolutePath()
                        .normalize();
        if (source.equals(output))
            throw new IllegalArgumentException("Output cannot overwrite input");
        Map<String, List<DailyBar>> bySymbol = new TreeMap<>();
        List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
        String[] header = lines.getFirst().replace("\uFEFF", "").split(",");
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < header.length; i++) columns.put(header[i], i);
        for (String required : List.of("date", "symbol", "open", "high", "low", "close", "volume"))
            if (!columns.containsKey(required))
                throw new IllegalArgumentException("Missing column " + required);
        long floatingPointRepairs = 0;
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) continue;
            String[] row = line.split(",", -1);
            String symbol = row[columns.get("symbol")];
            double open = Double.parseDouble(row[columns.get("open")]);
            double high = Double.parseDouble(row[columns.get("high")]);
            double low = Double.parseDouble(row[columns.get("low")]);
            double close = Double.parseDouble(row[columns.get("close")]);
            double correctedHigh = Math.max(high, Math.max(open, close));
            double correctedLow = Math.min(low, Math.min(open, close));
            double tolerance =
                    Math.max(
                                    Math.abs(high),
                                    Math.max(
                                            Math.abs(low),
                                            Math.max(Math.abs(open), Math.abs(close))))
                            * 1e-10;
            if (Math.max(Math.abs(correctedHigh - high), Math.abs(correctedLow - low)) > tolerance)
                throw new IllegalArgumentException(
                        "Invalid OHLC ordering " + symbol + " " + row[columns.get("date")]);
            if (correctedHigh != high || correctedLow != low) floatingPointRepairs++;
            DailyBar bar =
                    new DailyBar(
                            LocalDate.parse(row[columns.get("date")]),
                            open,
                            correctedHigh,
                            correctedLow,
                            close,
                            new BigDecimal(row[columns.get("volume")]).longValueExact());
            if (!bar.valid())
                throw new IllegalArgumentException("Invalid OHLC " + symbol + " " + bar.date());
            bySymbol.computeIfAbsent(symbol, ignored -> new ArrayList<>()).add(bar);
        }
        for (var bars : bySymbol.values()) {
            bars.sort(Comparator.comparing(DailyBar::date));
            for (int i = 1; i < bars.size(); i++)
                if (!bars.get(i).date().isAfter(bars.get(i - 1).date()))
                    throw new IllegalArgumentException("Duplicate daily bar");
        }
        var spy = Objects.requireNonNull(bySymbol.get("SPY"), "SPY required");
        var qqq = Objects.requireNonNull(bySymbol.get("QQQ"), "QQQ required");
        NavigableSet<LocalDate> calendar = new TreeSet<>();
        spy.forEach(bar -> calendar.add(bar.date()));
        var settings = new KiwoomUsStrategySettings();
        settings.setSignalMode(KiwoomUsStrategySettings.SignalMode.TREND);
        List<Map<String, Object>> signals = new ArrayList<>();
        // The production calendar intentionally supports 2026-2028 only. Override ONLY its
        // previousTradingDay boundary in this scoped test using observed SPY sessions.
        try (var clock = mockStatic(KiwoomUsMarketHours.class)) {
            clock.when(() -> KiwoomUsMarketHours.previousTradingDay(any(LocalDate.class)))
                    .thenAnswer(
                            call -> {
                                var previous = calendar.lower(call.getArgument(0));
                                if (previous == null)
                                    throw new IllegalStateException("Calendar warmup missing");
                                return previous;
                            });
            for (var entry : bySymbol.entrySet()) {
                if (entry.getKey().equals("SPY") || entry.getKey().equals("QQQ")) continue;
                var bars = entry.getValue();
                for (int i = 51; i < bars.size(); i++) {
                    var current = bars.get(i);
                    var date = current.date();
                    var stock = List.copyOf(bars.subList(i - 51, i));
                    var spyPast = spy.stream().filter(bar -> bar.date().isBefore(date)).toList();
                    var qqqPast = qqq.stream().filter(bar -> bar.date().isBefore(date)).toList();
                    if (spyPast.size() < 51 || qqqPast.size() < 51) continue;
                    var record = new LinkedHashMap<String, Object>();
                    record.put("date", date.toString());
                    record.put("symbol", entry.getKey());
                    record.put("signalPrice", current.close());
                    try {
                        var quote =
                                new OrderBookQuote(
                                        BigDecimal.valueOf(current.close()),
                                        BigDecimal.valueOf(current.close()));
                        var signal =
                                KiwoomUsTechnicalSignalService.calculate(
                                        stock,
                                        spyPast.subList(spyPast.size() - 51, spyPast.size()),
                                        qqqPast.subList(qqqPast.size() - 51, qqqPast.size()),
                                        quote,
                                        settings,
                                        date);
                        record.put("accepted", signal.accepted());
                        record.put("available", signal.available());
                        record.put("relativeStrengthPercent", signal.relativeStrengthPercent());
                        record.put("atr", signal.atr());
                        record.put("stopPercent", signal.stopPercent());
                        record.put("reason", signal.reason());
                        record.put(
                                "dataDate",
                                signal.dataDate() == null ? null : signal.dataDate().toString());
                    } catch (IllegalStateException missing) {
                        record.put("accepted", false);
                        record.put("available", false);
                        record.put("reason", missing.getMessage());
                    }
                    signals.add(record);
                    clock.clearInvocations();
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(
                "adapter",
                "production calculate; prior 51 bars; close(t) zero-spread quote; observed SPY calendar only");
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("maxOrderPercent", settings.getMaxOrderPercent());
        configuration.put("riskPerTradePercent", settings.getRiskPerTradePercent());
        configuration.put("maxPositions", settings.getMaxPositions());
        configuration.put("dailyMaxBuys", settings.getDailyMaxBuys());
        configuration.put("maxHoldingTradingDays", settings.getMaxHoldingTradingDays());
        configuration.put("trailingStopAtrMultiplier", settings.getTrailingStopAtrMultiplier());
        configuration.put("trailingActivationR", settings.getTrailingActivationR());
        configuration.put("symbolCooldownDays", settings.getSymbolCooldownDays());
        configuration.put("minRelativeStrengthPercent", settings.getMinRelativeStrengthPercent());
        configuration.put("atrStopMultiplier", settings.getAtrStopMultiplier());
        configuration.put("maxEntryExtensionAtr", settings.getMaxEntryExtensionAtr());
        configuration.put("stopLossPercent", settings.getStopLossPercent());
        configuration.put("dailyLossLimitPercent", settings.getDailyLossLimitPercent());
        result.put("settings", configuration);
        Map<String, String> hashes = new LinkedHashMap<>();
        for (var item :
                Map.of(
                                "input",
                                source,
                                "signalSource",
                                Path.of(
                                        "src/main/java/com/hyunchang/webapp/service/KiwoomUsTechnicalSignalService.java"),
                                "settingsSource",
                                Path.of(
                                        "src/main/java/com/hyunchang/webapp/entity/KiwoomUsStrategySettings.java"))
                        .entrySet())
            hashes.put(
                    item.getKey(),
                    HexFormat.of()
                            .formatHex(
                                    java.security.MessageDigest.getInstance("SHA-256")
                                            .digest(Files.readAllBytes(item.getValue()))));
        result.put("sha256", hashes);
        result.put("floatingPointRepairs", floatingPointRepairs);
        result.put("roundoffToleranceRelative", 1e-10);
        result.put("signals", signals);
        Files.createDirectories(output.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), result);
        System.out.println("OFFLINE_REPLAY_SIGNALS=" + signals.size() + " output=" + output);
    }
}
