package com.hyunchang.webapp.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class YahooFinanceQuoteTest {
    private final RestTemplate rest = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
    private final YahooFinanceService service = new YahooFinanceService(rest, new ObjectMapper());

    private String url(String host, String symbol) {
        return "https://"
                + host
                + ".finance.yahoo.com/v8/finance/chart/"
                + symbol
                + "?interval=1d&range=1d";
    }

    private String quote(double price) {
        return "{\"chart\":{\"result\":[{\"meta\":{\"regularMarketPrice\":"
                + price
                + ",\"chartPreviousClose\":100}}],\"error\":null}}";
    }

    @Test
    void primarySuccessDoesNotRetry() {
        server.expect(requestTo(url("query1", "QLD")))
                .andRespond(withSuccess(quote(105), MediaType.APPLICATION_JSON));
        var result = service.fetchQuote("QLD", "USD");
        assertNotNull(result);
        assertEquals(105, result.getPrice());
        assertEquals(5, result.getChangePercent());
        server.verify();
    }

    @Test
    void rateLimitRetriesSecondaryOnce() {
        server.expect(requestTo(url("query1", "SCHD")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(url("query2", "SCHD")))
                .andRespond(withSuccess(quote(102), MediaType.APPLICATION_JSON));
        assertEquals(102, service.fetchQuote("SCHD", "USD").getPrice());
        server.verify();
    }

    @Test
    void emptyResultFallsBackWithoutNullDereference() {
        server.expect(requestTo(url("query1", "QLD")))
                .andRespond(
                        withSuccess("{\"chart\":{\"result\":null}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(url("query2", "QLD")))
                .andRespond(withSuccess(quote(103), MediaType.APPLICATION_JSON));
        assertEquals(103, service.fetchQuote("QLD", "USD").getPrice());
        server.verify();
    }

    @Test
    void invalidPricesNeverBecomeSuccessfulZeroQuotes() {
        server.expect(requestTo(url("query1", "QLD")))
                .andRespond(withSuccess(quote(0), MediaType.APPLICATION_JSON));
        server.expect(requestTo(url("query2", "QLD")))
                .andRespond(withSuccess(quote(-1), MediaType.APPLICATION_JSON));
        assertNull(service.fetchQuote("QLD", "USD"));
        server.verify();
    }

    @Test
    void foreignExchangeSymbolIsEncodedAndPreserved() {
        server.expect(requestTo(url("query1", "USDKRW%3DX")))
                .andRespond(withSuccess(quote(1300), MediaType.APPLICATION_JSON));
        var result = service.fetchQuote("USDKRW=X", "USD");
        assertEquals("USDKRW=X", result.getSymbol());
        assertEquals(1300, result.getPrice());
        server.verify();
    }

    @Test
    void bothHostsUnavailableReturnFailure() {
        server.expect(requestTo(url("query1", "SCHD"))).andRespond(withServerError());
        server.expect(requestTo(url("query2", "SCHD"))).andRespond(withServerError());
        assertNull(service.fetchQuote("SCHD", "USD"));
        server.verify();
    }

    @Test
    void missingPreviousCloseDoesNotFabricateDailyGain() {
        String body = "{\"chart\":{\"result\":[{\"meta\":{\"regularMarketPrice\":105}}]}}";
        server.expect(requestTo(url("query1", "QLD")))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        server.expect(requestTo(url("query2", "QLD")))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertNull(service.fetchQuote("QLD", "USD"));
        server.verify();
    }
}
