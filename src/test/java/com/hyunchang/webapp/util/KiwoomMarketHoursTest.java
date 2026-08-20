package com.hyunchang.webapp.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class KiwoomMarketHoursTest {

    @Test
    void weekendsAndKrxClosedDatesAreNotTradingDays() {
        assertFalse(KiwoomMarketHours.isTradingDay(LocalDate.of(2026, 8, 8)));
        assertFalse(KiwoomMarketHours.isTradingDay(LocalDate.of(2026, 8, 17)));
        assertFalse(KiwoomMarketHours.isTradingDay(LocalDate.of(2026, 6, 3)));
    }

    @Test
    void regularWeekdayIsTradingDay() {
        assertTrue(KiwoomMarketHours.isTradingDay(LocalDate.of(2026, 8, 7)));
    }

    @Test
    void entryWindowRunsFromNineThirtyUntilTwoPm() {
        LocalDate tradingDay = LocalDate.of(2026, 8, 7);
        assertFalse(
                KiwoomMarketHours.isEntryWindow(
                        LocalDateTime.of(tradingDay, java.time.LocalTime.of(9, 29, 59))));
        assertTrue(
                KiwoomMarketHours.isEntryWindow(
                        LocalDateTime.of(tradingDay, java.time.LocalTime.of(9, 30))));
        assertFalse(
                KiwoomMarketHours.isEntryWindow(
                        LocalDateTime.of(tradingDay, java.time.LocalTime.of(14, 0))));
    }
}
