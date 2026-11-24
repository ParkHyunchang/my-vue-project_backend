package com.hyunchang.webapp.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.config.KiwoomProperties;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class KiwoomAccountOverviewServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final KiwoomProperties properties = mock(KiwoomProperties.class);
    private final KiwoomTradeService kr = mock(KiwoomTradeService.class);
    private final KiwoomUsTradeService us = mock(KiwoomUsTradeService.class);
    private final KiwoomAccountOverviewService service =
            new KiwoomAccountOverviewService(properties, kr, us);

    private Mono<com.fasterxml.jackson.databind.JsonNode> response(String body) throws Exception {
        return Mono.just(json.readTree(body));
    }

    private void ready() throws Exception {
        when(properties.isConfigured()).thenReturn(true);
        when(kr.getBalanceForDisplay())
                .thenReturn(
                        response(
                                "{\"return_code\":0,\"acnt_evlt_remn_indv_tot\":[{\"stk_cd\":\"A0167A0\",\"stk_nm\":\"Example ETF\",\"rmnd_qty\":\"1.25\",\"pur_pric\":\"123.45\"}]}"));
        when(kr.getDepositForDisplay())
                .thenReturn(response("{\"return_code\":0,\"entr\":\"1,200\"}"));
        when(us.getBalanceForDisplay())
                .thenReturn(
                        response(
                                "{\"return_code\":0,\"result_list\":[{\"stk_cd\":\"DEMO\",\"poss_qty\":\"0.125\",\"now_pric\":\"50.15\",\"pl_amt\":\"-2.5\"}]}"));
        when(us.getDepositForDisplay())
                .thenReturn(response("{\"return_code\":0,\"d0_usd_fx_entr\":\"20.25\"}"));
    }

    @Test
    void displayPreservesFractionsNullsAndNoAccountIdentifiers() throws Exception {
        ready();
        var result = service.overview();
        var domestic = result.markets().get(0);
        var foreign = result.markets().get(1);
        assertEquals("OK", domestic.status());
        assertEquals("0167A0", domestic.holdings().get(0).symbol());
        assertEquals(new BigDecimal("1.25"), domestic.holdings().get(0).quantity());
        assertNull(domestic.holdings().get(0).currentPrice());
        assertNull(domestic.holdings().get(0).profitLoss());
        assertNull(domestic.dataAsOf());
        assertNotNull(domestic.queriedAt());
        assertEquals(new BigDecimal("0.125"), foreign.holdings().get(0).quantity());
        assertEquals(new BigDecimal("-2.5"), foreign.holdings().get(0).profitLoss());
        assertEquals(new BigDecimal("20.25"), foreign.cash());
        verify(kr, never()).getBalance();
        verify(us, never()).getBalance();
        verify(properties, never()).getAccountNo();
    }

    @Test
    void oneMarketFailureCannotEraseOtherMarketAndCachePreventsDuplicateReads() throws Exception {
        ready();
        when(kr.getBalanceForDisplay())
                .thenReturn(Mono.error(new IllegalStateException("private account data")));
        var first = service.overview();
        var second = service.overview();
        assertSame(first, second);
        assertEquals("ERROR", first.markets().get(0).status());
        assertFalse(first.markets().get(0).notice().contains("private"));
        assertEquals("OK", first.markets().get(1).status());
        verify(us, times(1)).getBalanceForDisplay();
    }

    @Test
    void unconfiguredDoesNotCallBroker() {
        var result = service.overview();
        assertTrue(result.markets().stream().allMatch(m -> m.status().equals("UNCONFIGURED")));
        verifyNoInteractions(kr, us);
    }

    @Test
    void emptyAndMissingListsAreNotConfused() throws Exception {
        ready();
        when(kr.getBalanceForDisplay())
                .thenReturn(response("{\"return_code\":0,\"acnt_evlt_remn_indv_tot\":[]}"));
        when(us.getBalanceForDisplay()).thenReturn(response("{\"return_code\":0}"));
        var result = service.overview();
        assertEquals("OK", result.markets().get(0).status());
        assertTrue(result.markets().get(0).holdings().isEmpty());
        assertEquals("ERROR", result.markets().get(1).status());
    }

    @Test
    void concurrentReadersShareOneBrokerFetch() throws Exception {
        ready();
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(kr.getBalanceForDisplay())
                .thenReturn(
                        Mono.fromCallable(
                                () -> {
                                    started.countDown();
                                    if (!release.await(3, java.util.concurrent.TimeUnit.SECONDS))
                                        throw new IllegalStateException("test timeout");
                                    return json.readTree(
                                            "{\"return_code\":0,\"acnt_evlt_remn_indv_tot\":[]}");
                                }));
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(service::overview);
            assertTrue(started.await(3, java.util.concurrent.TimeUnit.SECONDS));
            var second = executor.submit(service::overview);
            release.countDown();
            assertSame(
                    first.get(3, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(3, java.util.concurrent.TimeUnit.SECONDS));
            verify(kr, times(1)).getBalanceForDisplay();
            verify(us, times(1)).getBalanceForDisplay();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
