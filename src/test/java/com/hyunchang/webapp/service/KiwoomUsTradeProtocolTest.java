package com.hyunchang.webapp.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.service.kiwoom.KiwoomUsAutoTradeState;
import com.hyunchang.webapp.util.KiwoomUsMarketHours;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;

class KiwoomUsTradeProtocolTest {
    private final List<ClientRequest> requests = new ArrayList<>();

    private KiwoomUsTradeService service(ClientResponse... responses) {
        var properties = new KiwoomProperties();
        properties.setMinRequestIntervalMs(0);
        properties.getUs().setTradeEnabled(true);
        var auth = mock(KiwoomAuthService.class);
        when(auth.getAccessToken()).thenReturn(Mono.just("test-token"));
        var builder =
                WebClient.builder()
                        .exchangeFunction(
                                request -> {
                                    requests.add(request);
                                    return Mono.just(
                                            responses[
                                                    Math.min(
                                                            requests.size() - 1,
                                                            responses.length - 1)]);
                                });
        return new KiwoomUsTradeService(
                properties, auth, mock(KiwoomUsAutoTradeState.class), builder);
    }

    private ClientResponse response(String body, String more, String key) {
        return ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .header("cont-yn", more)
                .header("next-key", key)
                .body(body)
                .build();
    }

    @Test
    void countryOnlyHoldingResolvesThroughOfficialLookupAndReusesSameDayResult() {
        String balance =
                """
                {"return_code":0,"result_list":[{"stk_cd":"JEPQ","stex_nm":"미국",
                "poss_qty":"5","sell_alowq":"5","now_pric":"60","evlt_amt":"300"}]}
                """;
        var trade =
                service(
                        response(balance, "N", ""),
                        response(
                                """
                        {"return_code":0,"list":[{"stk_cd":"JEPQ","stex_tp":"ND"}]}
                        """,
                                "N",
                                ""),
                        response(balance, "N", ""));
        var first = trade.holdings(trade.getBalance().block());
        var second = trade.holdings(trade.getBalance().block());
        assertEquals("ND", first.getFirst().exchange());
        assertEquals(5, second.getFirst().quantity());
        assertEquals(new BigDecimal("300"), second.getFirst().evaluationAmount());
        assertEquals(
                List.of("ust21070", "usa10098", "ust21070"),
                requests.stream().map(r -> r.headers().getFirst("api-id")).toList());
        assertEquals("/api/us/stkinfo", requests.get(1).url().getPath());
    }

    @Test
    void knownExchangeAndZeroQuantityNeedNoLookup() {
        var trade =
                service(
                        response(
                                """
                {"return_code":0,"result_list":[
                {"stk_cd":"JPM","stex_nm":"NYSE","poss_qty":"2"},
                {"stk_cd":"OLD","stex_nm":"미국","poss_qty":"0"}]}
                """,
                                "N",
                                ""));
        assertEquals("NY", trade.holdings(trade.getBalance().block()).getFirst().exchange());
        assertEquals(1, requests.size());
    }

    @Test
    void unrelatedAmbiguousOrMalformedLookupCannotReturnPartialHoldings() {
        for (String rows :
                List.of(
                        "[{\"stk_cd\":\"OTHER\",\"stex_tp\":\"ND\"}]",
                        "[{\"stk_cd\":\"JEPQ\",\"stex_tp\":\"ND\"},{\"stk_cd\":\"JEPQ\",\"stex_tp\":\"NY\"}]",
                        "[]",
                        "null")) {
            requests.clear();
            var trade =
                    service(
                            response(
                                    """
                    {"return_code":0,"result_list":[
                    {"stk_cd":"JPM","stex_nm":"NYSE","poss_qty":"1"},
                    {"stk_cd":"JEPQ","stex_nm":"미국","poss_qty":"5"}]}
                    """,
                                    "N",
                                    ""),
                            response("{\"return_code\":0,\"list\":" + rows + "}", "N", ""));
            assertThrows(IllegalStateException.class, () -> trade.getBalance().block());
        }
    }

    @Test
    void failedLookupIsNotCachedAndNextBalanceCanRecover() {
        String balance =
                """
                {"return_code":0,"result_list":[{"stk_cd":"JEPQ","stex_nm":"미국","poss_qty":"5"}]}
                """;
        var trade =
                service(
                        response(balance, "N", ""),
                        response(
                                "{\"return_code\":123,\"return_msg\":\"temporary failure\"}",
                                "N",
                                ""),
                        response(balance, "N", ""),
                        response(
                                "{\"return_code\":0,\"list\":[{\"stk_cd\":\"JEPQ\",\"stex_tp\":\"ND\"}]}",
                                "N",
                                ""));
        assertThrows(RuntimeException.class, () -> trade.getBalance().block());
        assertEquals("ND", trade.holdings(trade.getBalance().block()).getFirst().exchange());
        assertEquals(4, requests.size());
    }

    @Test
    void exchangeCacheExpiresOnNextEasternDate() {
        String balance =
                """
                {"return_code":0,"result_list":[{"stk_cd":"TEST","stex_nm":"미국","poss_qty":"1"}]}
                """;
        var trade =
                service(
                        response(balance, "N", ""),
                        response(
                                "{\"return_code\":0,\"list\":[{\"stk_cd\":\"TEST\",\"stex_tp\":\"ND\"}]}",
                                "N",
                                ""),
                        response(balance, "N", ""),
                        response(
                                "{\"return_code\":0,\"list\":[{\"stk_cd\":\"TEST\",\"stex_tp\":\"NY\"}]}",
                                "N",
                                ""));
        try (var hours = mockStatic(KiwoomUsMarketHours.class)) {
            hours.when(KiwoomUsMarketHours::today).thenReturn(LocalDate.of(2026, 10, 2));
            assertEquals("ND", trade.holdings(trade.getBalance().block()).getFirst().exchange());
            hours.when(KiwoomUsMarketHours::today).thenReturn(LocalDate.of(2026, 10, 3));
            assertEquals("NY", trade.holdings(trade.getBalance().block()).getFirst().exchange());
        }
    }

    @Test
    void balanceAggregatesEveryPageAndForwardsContinuationHeaders() {
        var trade =
                service(
                        response(
                                "{\"return_code\":0,\"tot_evlt_amt\":\"30\",\"result_list\":[{\"stk_cd\":\"A\"}]}",
                                "Y",
                                "page2"),
                        response(
                                "{\"return_code\":0,\"result_list\":[{\"stk_cd\":\"B\"}]}",
                                "N",
                                ""));
        var result = trade.getBalance().block();
        assertEquals(2, result.path("result_list").size());
        assertEquals("30", result.path("tot_evlt_amt").asText());
        assertEquals("Y", requests.get(1).headers().getFirst("cont-yn"));
        assertEquals("page2", requests.get(1).headers().getFirst("next-key"));
    }

    @Test
    void incompleteOrLoopingPaginationNeverReturnsPartialAccount() {
        var trade = service(response("{\"return_code\":0,\"result_list\":[]}", "Y", ""));
        assertThrows(IllegalStateException.class, () -> trade.getBalance().block());
        requests.clear();
        var looping = service(response("{\"return_code\":0,\"result_list\":[]}", "Y", "same"));
        assertThrows(IllegalStateException.class, () -> looping.getOpenOrders().block());
        assertEquals(2, requests.size());
    }

    @Test
    void historicalApiAcceptsPublishedResultListSpelling() {
        var trade =
                service(
                        response(
                                "{\"return_code\":0,\"result_lsit\":[{\"ord_no\":\"123\"}]}",
                                "N",
                                ""));
        var result =
                trade.getOrderHistory(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2)).block();
        assertEquals("123", result.path("result_list").get(0).path("ord_no").asText());
        assertEquals("ust21180", requests.getFirst().headers().getFirst("api-id"));
    }

    @Test
    void malformedWriteResponseRemainsAmbiguousAndIsNeverRetried() {
        var trade = service(response("{\"unexpected\":true}", "N", ""));
        try (var hours = mockStatic(KiwoomUsMarketHours.class)) {
            hours.when(KiwoomUsMarketHours::isOpen).thenReturn(true);
            var error =
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    trade.placeOrder(
                                                    new KiwoomUsTradeService.Order(
                                                            "SELL",
                                                            "ND",
                                                            "TEST",
                                                            1,
                                                            BigDecimal.TEN,
                                                            false))
                                            .block());
            assertFalse(KiwoomUsTradeService.isDefinitiveOrderFailure(error));
            assertEquals(1, requests.size());
        }
    }

    @Test
    void explicitWriteRejectionIsDefinitiveAndIsNeverRetried() {
        var trade = service(response("{\"return_code\":123,\"return_msg\":\"rejected\"}", "N", ""));
        var error =
                assertThrows(
                        IllegalStateException.class,
                        () -> trade.cancelOrder("ND", "TEST", "123", 1).block());
        assertTrue(KiwoomUsTradeService.isDefinitiveOrderFailure(error));
        assertEquals(1, requests.size());
    }
}
