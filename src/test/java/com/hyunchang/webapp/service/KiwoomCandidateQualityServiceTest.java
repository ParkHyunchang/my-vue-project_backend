package com.hyunchang.webapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hyunchang.webapp.entity.KiwoomStrategySettings;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class KiwoomCandidateQualityServiceTest {

    @Test
    void acceptsCandidateOnlyAfterLiquidityTrendAtrAndSpreadPass() {
        KiwoomTradeService trade = mock(KiwoomTradeService.class);
        when(trade.getDailyChart("005930")).thenReturn(Mono.just(uptrendCandles()));
        when(trade.getOrderBookQuote("005930"))
                .thenReturn(Mono.just(new KiwoomTradeService.OrderBookQuote(1_099, 1_101)));

        KiwoomStrategySettings settings = conservativeSettings();
        KrxOpenApiService.KrSwingCandidate candidate =
                new KrxOpenApiService.KrSwingCandidate(
                        "005930.KS",
                        "삼성전자",
                        "KOSPI",
                        1_100,
                        3.0,
                        1_000_000,
                        300_000,
                        2.0,
                        LocalDate.now(),
                        1.0,
                        500_000_000_000L,
                        20_000_000_000L);

        KiwoomCandidateQualityService.CandidateQuality result =
                new KiwoomCandidateQualityService(trade).evaluate(candidate, settings);

        assertTrue(result.accepted());
        assertFalse(result.dataMissing());
        assertEquals("001", result.sectorCode());
        assertTrue(result.ma5() > result.ma20());
        assertTrue(result.atrPercent() <= 4.0);
        assertTrue(result.spreadPercent() <= 0.3);
    }

    @Test
    void rejectsExcessiveLiveSpread() {
        KiwoomTradeService trade = mock(KiwoomTradeService.class);
        when(trade.getDailyChart("005930")).thenReturn(Mono.just(uptrendCandles()));
        when(trade.getOrderBookQuote("005930"))
                .thenReturn(Mono.just(new KiwoomTradeService.OrderBookQuote(1_090, 1_110)));
        KrxOpenApiService.KrSwingCandidate candidate =
                new KrxOpenApiService.KrSwingCandidate(
                        "005930.KS",
                        "삼성전자",
                        "KOSPI",
                        1_100,
                        3.0,
                        1_000_000,
                        300_000,
                        2.0,
                        LocalDate.now(),
                        1.0,
                        500_000_000_000L,
                        20_000_000_000L);

        KiwoomCandidateQualityService.CandidateQuality result =
                new KiwoomCandidateQualityService(trade)
                        .evaluate(candidate, conservativeSettings());

        assertFalse(result.accepted());
        assertEquals("호가 스프레드", result.gate());
    }

    @Test
    void acceptsValidPriceHistoryWhenKiwoomOmitsSectorCode() {
        KiwoomTradeService trade = mock(KiwoomTradeService.class);
        List<KiwoomTradeService.DailyCandle> candles =
                uptrendCandles().stream()
                        .map(
                                candle ->
                                        new KiwoomTradeService.DailyCandle(
                                                candle.date(),
                                                candle.close(),
                                                candle.high(),
                                                candle.low(),
                                                candle.volume(),
                                                ""))
                        .toList();
        when(trade.getDailyChart("005930")).thenReturn(Mono.just(candles));
        when(trade.getOrderBookQuote("005930"))
                .thenReturn(Mono.just(new KiwoomTradeService.OrderBookQuote(1_099, 1_101)));

        KiwoomCandidateQualityService.CandidateQuality result =
                new KiwoomCandidateQualityService(trade)
                        .evaluate(candidate(20_000_000_000L), conservativeSettings());

        assertTrue(result.accepted());
        assertEquals("MARKET:KOSPI", result.sectorCode());
    }

    @Test
    void reportsExactValidCandleCountWhenHistoryIsShort() {
        KiwoomTradeService trade = mock(KiwoomTradeService.class);
        when(trade.getDailyChart("005930")).thenReturn(Mono.just(uptrendCandles().subList(0, 20)));

        KiwoomCandidateQualityService.CandidateQuality result =
                new KiwoomCandidateQualityService(trade)
                        .evaluate(candidate(20_000_000_000L), conservativeSettings());

        assertTrue(result.dataMissing());
        assertTrue(result.detail().contains("유효=20개"));
    }

    @Test
    void intradayTradingValueThresholdFollowsExpectedCumulativeCurve() {
        assertEquals(
                1_800_000_000L,
                KiwoomCandidateQualityService.intradayTradingValueThreshold(
                        10_000_000_000L, LocalTime.of(9, 30)));
        assertEquals(
                10_000_000_000L,
                KiwoomCandidateQualityService.intradayTradingValueThreshold(
                        10_000_000_000L, LocalTime.of(15, 30)));
    }

    private KrxOpenApiService.KrSwingCandidate candidate(long tradingValue) {
        return new KrxOpenApiService.KrSwingCandidate(
                "005930.KS",
                "삼성전자",
                "KOSPI",
                1_100,
                3.0,
                1_000_000,
                300_000,
                2.0,
                LocalDate.now(),
                1.0,
                500_000_000_000L,
                tradingValue);
    }

    private KiwoomStrategySettings conservativeSettings() {
        KiwoomStrategySettings settings = new KiwoomStrategySettings();
        settings.setMinMarketCapWon(300_000_000_000L);
        settings.setMinTradingValueWon(10_000_000_000L);
        settings.setMaxPriceAboveMa20Percent(10);
        settings.setMaxAtrPercent(4);
        settings.setMaxSpreadPercent(0.3);
        return settings;
    }

    private List<KiwoomTradeService.DailyCandle> uptrendCandles() {
        List<KiwoomTradeService.DailyCandle> candles = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            long close = 1_100 - i * 5L;
            candles.add(
                    new KiwoomTradeService.DailyCandle(
                            LocalDate.now().minusDays(i),
                            close,
                            close + 10,
                            close - 10,
                            100_000,
                            "001"));
        }
        return candles;
    }
}
