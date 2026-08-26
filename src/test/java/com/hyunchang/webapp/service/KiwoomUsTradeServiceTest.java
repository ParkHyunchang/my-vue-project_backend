package com.hyunchang.webapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.service.kiwoom.KiwoomUsAutoTradeState;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class KiwoomUsTradeServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void relativeVolumeIsAdjustedForElapsedRegularSessionTime() {
        var stock =
                new KiwoomUsTradeService.RankedStock(
                        1,
                        "NAS",
                        "TEST",
                        "Test",
                        BigDecimal.TEN,
                        3,
                        100,
                        100,
                        BigDecimal.valueOf(1_000));

        assertEquals(2.0, stock.relativeVolumeRatio(0.5), 0.000_001);
        assertEquals(1.0, stock.relativeVolumeRatio(1.0), 0.000_001);
    }

    @Test
    void orderBookSpreadUsesTheBidAskMidpoint() {
        var quote =
                new KiwoomUsTradeService.OrderBookQuote(
                        new BigDecimal("99.90"), new BigDecimal("100.10"));

        assertEquals(0.2, quote.spreadPercent(), 0.000_001);
    }

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

    @Test
    void accountHoldingUsesSpecificExchangeNameWhenCountryLabelComesFirst() throws Exception {
        KiwoomUsTradeService service = service(mock(KiwoomUsAutoTradeState.class), "{}");

        var holdings =
                service.holdings(
                        JSON.readTree(
                                """
                                {"result_list":[{
                                  "stex_tp":"미국", "stex_nm":"NASDAQ", "stk_cd":"NVDA",
                                  "poss_qty":"2", "sell_alowq":"2", "now_pric":"180.25"
                                }]}
                                """));

        assertEquals(1, holdings.size());
        assertEquals("ND", holdings.getFirst().exchange());
    }

    @Test
    void rankedStockUsesSpecificExchangeFieldInsteadOfAggregateCountry() {
        KiwoomUsAutoTradeState state = mock(KiwoomUsAutoTradeState.class);
        KiwoomUsTradeService service =
                service(
                        state,
                        """
                        {"return_code":0,"result_list":[{
                          "rank":"1", "stex_tp":"미국", "ovrs_excg_cd":"NY",
                          "stk_cd":"JPM", "stk_nm":"JPMorgan", "cur_prc":"300",
                          "flu_rt":"2.5", "acc_trde_qty":"100", "pred_trde_qty":"50",
                          "trde_prica":"30000"
                        }]}
                        """);

        var ranked = service.getTradeValueTop().block();

        assertEquals(1, ranked.size());
        assertEquals("NY", ranked.getFirst().exchange());
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
