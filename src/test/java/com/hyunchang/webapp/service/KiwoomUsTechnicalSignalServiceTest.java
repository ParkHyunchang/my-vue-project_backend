package com.hyunchang.webapp.service;

import static org.junit.jupiter.api.Assertions.*;

import com.hyunchang.webapp.entity.KiwoomUsStrategySettings;
import com.hyunchang.webapp.service.KiwoomUsTradeService.DailyBar;
import com.hyunchang.webapp.service.KiwoomUsTradeService.OrderBookQuote;
import com.hyunchang.webapp.util.KiwoomUsMarketHours;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class KiwoomUsTechnicalSignalServiceTest {
    private final LocalDate session = LocalDate.of(2026, 10, 2);
    private final KiwoomUsStrategySettings settings = new KiwoomUsStrategySettings();

    private List<DailyBar> bars(double slope) {
        List<LocalDate> dates = new ArrayList<>();
        LocalDate day = session;
        for (int i = 0; i < 61; i++) {
            day = KiwoomUsMarketHours.previousTradingDay(day);
            dates.add(day);
        }
        Collections.reverse(dates);
        List<DailyBar> bars = new ArrayList<>();
        for (int i = 0; i < dates.size(); i++) {
            double close = 100 + slope * i;
            bars.add(new DailyBar(dates.get(i), close, close + 0.5, close - 0.5, close, 1000));
        }
        return bars;
    }

    private OrderBookQuote quote(double bid) {
        return new OrderBookQuote(BigDecimal.valueOf(bid), BigDecimal.valueOf(bid + 0.01));
    }

    @Test
    void acceptsConfirmedBreakoutOfStrongerStockInRisingMarket() {
        var result =
                KiwoomUsTechnicalSignalService.calculate(
                        bars(0.5), bars(0.1), bars(0.15), quote(130.5), settings, session);
        assertTrue(result.accepted());
        assertTrue(result.relativeStrengthPercent() > 0);
        assertEquals(session.minusDays(1), result.dataDate());
        assertTrue(result.stopPercent() <= settings.getStopLossPercent());
    }

    @Test
    void rejectsWeakMarketAndChasingEvenWithPositiveRelativeStrength() {
        assertFalse(
                KiwoomUsTechnicalSignalService.calculate(
                                bars(0.5), bars(-0.1), bars(0.15), quote(130.5), settings, session)
                        .accepted());
        var chasing =
                KiwoomUsTechnicalSignalService.calculate(
                        bars(0.5), bars(0.1), bars(0.15), quote(135), settings, session);
        assertFalse(chasing.accepted());
        assertTrue(chasing.reason().contains("이격"));
    }

    @Test
    void askCrossingAloneIsNotABreakout() {
        var result =
                KiwoomUsTechnicalSignalService.calculate(
                        bars(0.5),
                        bars(0.1),
                        bars(0.15),
                        new OrderBookQuote(new BigDecimal("130.49"), new BigDecimal("130.51")),
                        settings,
                        session);
        assertFalse(result.accepted());
        assertTrue(result.reason().contains("돌파 대기"));
    }

    @Test
    void rejectsStaleAndIncompleteDailyBars() {
        var stale = bars(0.5);
        stale.removeLast();
        assertThrows(
                IllegalStateException.class,
                () ->
                        KiwoomUsTechnicalSignalService.calculate(
                                stale, bars(0.1), bars(0.15), quote(130.5), settings, session));
        var unfinished = bars(0.5);
        unfinished.add(new DailyBar(session, 131, 132, 130, 131, 100));
        assertThrows(
                IllegalStateException.class,
                () ->
                        KiwoomUsTechnicalSignalService.calculate(
                                unfinished,
                                bars(0.1),
                                bars(0.15),
                                quote(130.5),
                                settings,
                                session));
    }

    @Test
    void rejectsMisalignedTradingDatesAndExcessiveStopDistance() {
        var gap = bars(0.5);
        gap.remove(gap.size() - 10);
        assertThrows(
                IllegalStateException.class,
                () ->
                        KiwoomUsTechnicalSignalService.calculate(
                                gap, bars(0.1), bars(0.15), quote(130.5), settings, session));
        settings.setStopLossPercent(0.5);
        assertFalse(
                KiwoomUsTechnicalSignalService.calculate(
                                bars(0.5), bars(0.1), bars(0.15), quote(130.5), settings, session)
                        .accepted());
    }

    @Test
    void positionSizeHonorsBothRiskAndCashCaps() {
        assertEquals(
                25,
                KiwoomUsAutoTradeService.riskQuantity(
                        new BigDecimal("100"),
                        new BigDecimal("5000"),
                        new BigDecimal("10000"),
                        2,
                        settings));
        assertEquals(
                20,
                KiwoomUsAutoTradeService.riskQuantity(
                        new BigDecimal("100"),
                        new BigDecimal("2000"),
                        new BigDecimal("10000"),
                        2,
                        settings));
        assertEquals(
                0,
                KiwoomUsAutoTradeService.riskQuantity(
                        new BigDecimal("100"),
                        new BigDecimal("50"),
                        new BigDecimal("10000"),
                        2,
                        settings));
    }
}
