package com.hyunchang.webapp.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.service.kiwoom.KiwoomUsAutoTradeState;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class KiwoomUsTradeServiceTest {

    @Test
    void settlementWindowResponseDoesNotCountAsApiFailure() {
        KiwoomUsAutoTradeState state = mock(KiwoomUsAutoTradeState.class);
        KiwoomUsTradeService service =
                service(
                        state,
                        "{\"return_code\":2000,\"return_msg\":\"(572070:수도결제중입니다 .잠시후 다시 조회하세요.)\"}");

        RuntimeException error =
                assertThrows(RuntimeException.class, () -> service.getDepositDetail().block());

        assertTrue(KiwoomUsTradeService.isTemporaryAccountSettlementError(error));
        verify(state, never()).recordApiFailure(anyString(), anyString(), anyInt());
    }

    @Test
    void ordinaryApiErrorStillCountsAsApiFailure() {
        KiwoomUsAutoTradeState state = mock(KiwoomUsAutoTradeState.class);
        KiwoomUsTradeService service =
                service(state, "{\"return_code\":2000,\"return_msg\":\"일반 조회 오류\"}");

        assertThrows(RuntimeException.class, () -> service.getDepositDetail().block());

        verify(state).recordApiFailure(anyString(), anyString(), anyInt());
    }

    private KiwoomUsTradeService service(KiwoomUsAutoTradeState state, String responseBody) {
        KiwoomProperties properties = new KiwoomProperties();
        KiwoomAuthService auth = mock(KiwoomAuthService.class);
        when(auth.getAccessToken()).thenReturn(Mono.just("test-token"));
        WebClient.Builder client =
                WebClient.builder()
                        .exchangeFunction(
                                request ->
                                        Mono.just(
                                                ClientResponse.create(HttpStatus.OK)
                                                        .header("Content-Type", "application/json")
                                                        .body(responseBody)
                                                        .build()));
        return new KiwoomUsTradeService(properties, auth, state, client);
    }
}
