package com.hyunchang.webapp.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.service.kiwoom.KiwoomAutoTradeState;
import com.hyunchang.webapp.service.kiwoom.KiwoomUsAutoTradeState;
import com.hyunchang.webapp.util.KiwoomApiRateLimiter;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class KiwoomDisplayReadTest {
    private final KiwoomProperties properties = new KiwoomProperties();
    private final KiwoomAuthService auth = mock(KiwoomAuthService.class);
    private final KiwoomAutoTradeState krState = mock(KiwoomAutoTradeState.class);
    private final KiwoomUsAutoTradeState usState = mock(KiwoomUsAutoTradeState.class);

    private ClientResponse response(String body, boolean more) {
        return ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .header("cont-yn", more ? "Y" : "N")
                .header("next-key", more ? "next" : "")
                .body(body)
                .build();
    }

    @Test
    void displaySuccessAndFailuresNeverTouchTradingState() {
        when(auth.getAccessToken()).thenReturn(Mono.just("synthetic-token"));
        var builder =
                WebClient.builder()
                        .exchangeFunction(
                                request ->
                                        Mono.just(
                                                response(
                                                        "{\"return_code\":12,\"return_msg\":\"failed\"}",
                                                        false)));
        try (var limiter = mockStatic(KiwoomApiRateLimiter.class)) {
            var kr = new KiwoomTradeService(properties, auth, krState, builder);
            var us = new KiwoomUsTradeService(properties, auth, usState, builder);
            assertThrows(RuntimeException.class, () -> kr.getBalanceForDisplay().block());
            assertThrows(RuntimeException.class, () -> kr.getDepositForDisplay().block());
            assertThrows(RuntimeException.class, () -> us.getBalanceForDisplay().block());
            assertThrows(RuntimeException.class, () -> us.getDepositForDisplay().block());
            assertThrows(
                    RuntimeException.class, () -> us.getKrwOrderServiceStatusForDisplay().block());
            verifyNoInteractions(krState, usState);
        }
    }

    @Test
    void koreanDisplayReadsEveryPageAndPreservesAlphanumericCode() {
        when(auth.getAccessToken()).thenReturn(Mono.just("synthetic-token"));
        AtomicInteger calls = new AtomicInteger();
        var builder =
                WebClient.builder()
                        .exchangeFunction(
                                request -> {
                                    assertEquals("kt00018", request.headers().getFirst("api-id"));
                                    int call = calls.getAndIncrement();
                                    if (call > 0)
                                        assertEquals(
                                                "next", request.headers().getFirst("next-key"));
                                    return Mono.just(
                                            response(
                                                    "{\"return_code\":0,\"acnt_evlt_remn_indv_tot\":[{\"stk_cd\":\"A0167A0\",\"rmnd_qty\":\"0.5\"}]}",
                                                    call == 0));
                                });
        try (var limiter = mockStatic(KiwoomApiRateLimiter.class)) {
            var result =
                    new KiwoomTradeService(properties, auth, krState, builder)
                            .getBalanceForDisplay()
                            .block();
            assertEquals(2, result.path("acnt_evlt_remn_indv_tot").size());
            assertEquals(2, calls.get());
            verifyNoInteractions(krState);
        }
    }

    @Test
    void usDisplayAvoidsExchangeLookupAndIntegerFiltering() {
        when(auth.getAccessToken()).thenReturn(Mono.just("synthetic-token"));
        var builder =
                WebClient.builder()
                        .exchangeFunction(
                                request -> {
                                    assertEquals("ust21070", request.headers().getFirst("api-id"));
                                    return Mono.just(
                                            response(
                                                    "{\"return_code\":0,\"result_list\":[{\"stk_cd\":\"DEMO\",\"poss_qty\":\"0.125\",\"stex_nm\":\"미국\"}]}",
                                                    false));
                                });
        try (var limiter = mockStatic(KiwoomApiRateLimiter.class)) {
            var result =
                    new KiwoomUsTradeService(properties, auth, usState, builder)
                            .getBalanceForDisplay()
                            .block();
            assertEquals("0.125", result.path("result_list").path(0).path("poss_qty").asText());
            verifyNoInteractions(usState);
        }
    }

    @Test
    void repeatedContinuationKeyFailsRatherThanReturningIncompleteHoldings() {
        when(auth.getAccessToken()).thenReturn(Mono.just("synthetic-token"));
        AtomicInteger calls = new AtomicInteger();
        var builder =
                WebClient.builder()
                        .exchangeFunction(
                                request -> {
                                    calls.incrementAndGet();
                                    return Mono.just(
                                            response(
                                                    "{\"return_code\":0,\"acnt_evlt_remn_indv_tot\":[]}",
                                                    true));
                                });
        try (var limiter = mockStatic(KiwoomApiRateLimiter.class)) {
            var service = new KiwoomTradeService(properties, auth, krState, builder);
            assertThrows(RuntimeException.class, () -> service.getBalanceForDisplay().block());
            assertEquals(2, calls.get());
            verifyNoInteractions(krState);
        }
    }
}
