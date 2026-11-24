package com.hyunchang.webapp.service.paper;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import org.junit.jupiter.api.Test;

class UsPaperMarketServiceTest {
    private final UsPaperMarketService market = new UsPaperMarketService();

    @Test
    void dstHolidayEarlyCloseAndUnknownYearFailClosed() {
        assertTrue(market.isOpen(Instant.parse("2026-07-06T13:30:00Z")));
        assertFalse(market.isOpen(Instant.parse("2026-11-18T13:30:00Z")));
        assertTrue(market.isOpen(Instant.parse("2026-11-18T14:30:00Z")));
        assertFalse(market.isOpen(Instant.parse("2026-11-26T15:00:00Z")));
        assertTrue(market.isOpen(Instant.parse("2026-11-27T17:59:59Z")));
        assertFalse(market.isOpen(Instant.parse("2026-11-27T18:00:00Z")));
        assertFalse(market.isOpen(Instant.parse("2029-01-03T15:00:00Z")));
    }

    @Test
    void timestampMustBeNeitherStaleNorFuture() {
        Instant now = Instant.parse("2026-11-18T15:00:00Z");
        assertTrue(market.isFresh(now.minusSeconds(300), now));
        assertFalse(market.isFresh(now.minusSeconds(301), now));
        assertFalse(market.isFresh(now.plusSeconds(31), now));
        assertFalse(market.isFresh(null, now));
    }

    @Test
    void warmupCalendarIncludesSpecial2025Closure() {
        assertFalse(market.isTradingDay(LocalDate.of(2025, 1, 9)));
        assertEquals(LocalDate.of(2025, 1, 8), market.previousSession(LocalDate.of(2025, 1, 10)));
    }
}
