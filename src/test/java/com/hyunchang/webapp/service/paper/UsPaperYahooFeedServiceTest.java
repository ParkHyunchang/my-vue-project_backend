package com.hyunchang.webapp.service.paper;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import org.junit.jupiter.api.Test;

class UsPaperYahooFeedServiceTest {
    final Instant now = Instant.parse("2026-11-18T15:00:00Z");
    final UsPaperYahooFeedService provider =
            new UsPaperYahooFeedService(new ObjectMapper(), Clock.fixed(now, ZoneOffset.UTC));

    String json(String symbol, String currency, String price, String times, String closes) {
        return "{\"chart\":{\"result\":[{\"meta\":{\"symbol\":\""
                + symbol
                + "\",\"currency\":\""
                + currency
                + "\",\"regularMarketPrice\":"
                + price
                + ",\"regularMarketTime\":"
                + now.getEpochSecond()
                + "},\"timestamp\":"
                + times
                + ",\"indicators\":{\"quote\":[{\"close\":"
                + closes
                + "}]}}]}}";
    }

    @Test
    void incompleteCurrentDayIsExcludedFromSignal() throws Exception {
        long yesterday = Instant.parse("2026-11-17T14:30:00Z").getEpochSecond(),
                today = Instant.parse("2026-11-18T14:30:00Z").getEpochSecond();
        var result =
                provider.parse(
                        json("SPY", "USD", "100", "[" + yesterday + "," + today + "]", "[95,999]"),
                        now);
        assertEquals(1, result.completedDailyBars().size());
        assertEquals(LocalDate.of(2026, 11, 17), result.completedDailyBars().getFirst().date());
        assertEquals(now, result.quotedAt());
    }

    @Test
    void identityCurrencyMissingPriceAndHistoryReject() {
        assertThrows(
                IllegalArgumentException.class,
                () -> provider.parse(json("QQQ", "USD", "100", "[]", "[]"), now));
        assertThrows(
                IllegalArgumentException.class,
                () -> provider.parse(json("SPY", "KRW", "100", "[]", "[]"), now));
        assertThrows(
                IllegalArgumentException.class,
                () -> provider.parse(json("SPY", "USD", "null", "[]", "[]"), now));
        assertThrows(
                IllegalArgumentException.class,
                () -> provider.parse(json("SPY", "USD", "100", "[1,2]", "[90]"), now));
    }
}
