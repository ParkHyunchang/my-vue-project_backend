package com.hyunchang.webapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.config.KiwoomProperties;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class KiwoomTradeServiceTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void readsHoldingsAndEvaluationFromIndividualBalanceRows() throws Exception {
        var balance =
                json.readTree(
                        """
                        {
                          "tot_evlt_amt": "7,495",
                          "tot_evlt_pl": "-224",
                          "acnt_evlt_remn_indv_tot": [
                            {"stk_cd":"052460","stk_nm":"아이크래프트","rmnd_qty":"1","trde_able_qty":"1","pur_pric":"4,172","cur_prc":"-3,780","prft_rt":"-9.41","evltv_prft":"-392"},
                            {"stk_cd":"476080","stk_nm":"M83","rmnd_qty":"1","trde_able_qty":"1","pur_pric":"3,547","cur_prc":"3,715","prft_rt":"4.75","evltv_prft":"168"}
                          ]
                        }
                        """);

        KiwoomTradeService service = tradeService();

        var holdings = service.parseHoldings(balance);
        assertEquals(2, holdings.size());
        assertEquals(3_780, holdings.get(0).curPrice());
        assertEquals(-9.41, holdings.get(0).plPct());
        assertEquals(7_495, service.totalEvaluationAmount(balance));
        assertEquals(-224, service.totalEvaluationProfitLoss(balance));
    }

    @Test
    void requestsTheAccountBalanceTrForHoldings() {
        AtomicReference<String> apiId = new AtomicReference<>();
        WebClient.Builder clientBuilder =
                WebClient.builder()
                        .exchangeFunction(
                                request -> {
                                    apiId.set(request.headers().getFirst("api-id"));
                                    return Mono.just(
                                            ClientResponse.create(HttpStatus.OK)
                                                    .header("Content-Type", "application/json")
                                                    .body(
                                                            "{\"return_code\":0,\"return_msg\":\"ok\",\"tot_evlt_amt\":\"1\"}")
                                                    .build());
                                });
        KiwoomAuthService authService = mock(KiwoomAuthService.class);
        when(authService.getAccessToken()).thenReturn(Mono.just("test-token"));
        KiwoomTradeService service =
                new KiwoomTradeService(
                        new KiwoomProperties(),
                        authService,
                        mock(KiwoomAutoTradeState.class),
                        clientBuilder);

        service.getBalance().block();

        assertEquals("kt00018", apiId.get());
    }

    private KiwoomTradeService tradeService() {
        return new KiwoomTradeService(new KiwoomProperties(), null, null, WebClient.builder());
    }
}
