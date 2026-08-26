package com.hyunchang.webapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hyunchang.webapp.dto.StockNewsDto;
import java.util.List;
import org.junit.jupiter.api.Test;

class StockSymbolNewsServiceTest {

    @Test
    void distinguishesFoundNotFoundAndUnavailable() {
        StockNewsDto news =
                StockNewsDto.builder().title("수주 계약").link("https://example.com").build();

        assertEquals(
                StockSymbolNewsService.NewsLookupStatus.FOUND,
                new StockSymbolNewsService.SymbolNewsLookup(true, List.of(news), 3, 2).status());
        assertEquals(
                StockSymbolNewsService.NewsLookupStatus.NOT_FOUND,
                new StockSymbolNewsService.SymbolNewsLookup(true, List.of(), 3, 2).status());
        assertEquals(
                StockSymbolNewsService.NewsLookupStatus.UNAVAILABLE,
                new StockSymbolNewsService.SymbolNewsLookup(false, List.of(), 3, 0).status());
    }

    @Test
    void simplifiesSiteRestrictedGoogleQueryForFallback() {
        assertEquals(
                "대한항공",
                StockSymbolNewsService.simplifyGoogleQuery(
                        "대한항공 (site:hankyung.com OR site:mk.co.kr)"));
    }
}
