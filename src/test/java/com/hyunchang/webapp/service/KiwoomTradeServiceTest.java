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
    void usesEstimatedDepositAssetBeforeOrderableAmountForDailyLossCheck() throws Exception {
        var deposit = json.readTree("{\"entr\":\"1,000,000\",\"ord_alow_amt\":\"100,000\"}");
        var balance = json.readTree("{\"prsm_dpst_aset_amt\":\"1,250,000\",\"tot_evlt_amt\":\"250,000\"}");

        KiwoomTradeService.AccountAsset asset = tradeService().accountAsset(deposit, balance);

        assertEquals(1_250_000, asset.amount());
        assertEquals("추정예탁자산", asset.source());
    }

    @Test
    void fallsBackToDepositAndEvaluationWithoutEstimatedDepositAsset() throws Exception {
        var deposit = json.readTree("{\"entr\":\"1,000,000\",\"ord_alow_amt\":\"100,000\"}");
        var balance = json.readTree("{\"tot_evlt_amt\":\"250,000\"}");

        KiwoomTradeService.AccountAsset asset = tradeService().accountAsset(deposit, balance);

        assertEquals(1_250_000, asset.amount());
        assertEquals("예수금+보유평가금액", asset.source());
    }

    @Test
    void preservesZeroSellableQuantity() throws Exception {
        var balance =
                json.readTree(
                        """
                        {
                          "acnt_evlt_remn_indv_tot": [
                            {"stk_cd":"032940","stk_nm":"원익","rmnd_qty":"1","trde_able_qty":"0","pur_pric":"5,520","cur_prc":"6,080"}
                          ]
                        }
                        """);

        var holdings = tradeService().parseHoldings(balance);

        assertEquals(1, holdings.size());
        assertEquals(1, holdings.get(0).quantity());
        assertEquals(0, holdings.get(0).sellable());
    }

    @Test
    void describesAvailabilityFieldsForRejectedSellDiagnosis() throws Exception {
        var balance =
                json.readTree(
                        """
                        {
                          "acnt_evlt_remn_indv_tot": [
                            {"stk_cd":"032940","rmnd_qty":"1","trde_able_qty":"0","tdy_buyq":"1","tdy_sellq":"0","crd_tp_nm":"현금"}
                          ]
                        }
                        """);

        String diagnostic = tradeService().describeHoldingAvailability(balance, "032940");

        assertEquals("키움잔고[보유=1주, 매매가능=0주, 금일매수=1주, 금일매도=0주, 신용구분=현금]", diagnostic);
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
