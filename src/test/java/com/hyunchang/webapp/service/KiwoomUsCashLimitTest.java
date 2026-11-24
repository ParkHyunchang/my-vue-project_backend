package com.hyunchang.webapp.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.service.kiwoom.KiwoomUsAutoTradeState;
import com.hyunchang.webapp.util.KiwoomApiRateLimiter;
import com.hyunchang.webapp.util.KiwoomUsMarketHours;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class KiwoomUsCashLimitTest {
    private final ObjectMapper json = new ObjectMapper();
    private final List<String> requests = new ArrayList<>();

    private ObjectNode deposit() {
        return json.createObjectNode()
                .put("return_code", 0)
                .put("d0_usd_fx_entr", "1000")
                .put("krw_ord_set_amt", "0");
    }

    private ObjectNode orderability() {
        return json.createObjectNode()
                .put("return_code", 0)
                .put("krw_ord_rqst_yn", "N")
                .put("krw_ord_set_amt", "0")
                .put("krw_ord_alowa_100", "0")
                .put("krw_ord_alowq_100", "0")
                .put("min_ord_alowq", "99")
                .put("fc_entra", "1000")
                .put("fc_uncl_amt", "0")
                .put("ord_alowa", "1000")
                .put("min_ord_alowa", "1000");
    }

    private KiwoomUsTradeService service(ObjectNode deposit, ObjectNode orderability) {
        var properties = new KiwoomProperties();
        properties.getUs().setTradeEnabled(true);
        var auth = mock(KiwoomAuthService.class);
        when(auth.getAccessToken()).thenReturn(Mono.just("fixture-token"));
        var client =
                WebClient.builder()
                        .exchangeFunction(
                                request -> {
                                    String api = request.headers().getFirst("api-id");
                                    requests.add(api);
                                    String body =
                                            "ust21160".equals(api)
                                                    ? deposit.toString()
                                                    : requests.size() == 2
                                                            ? orderability.toString()
                                                            : "{\"return_code\":0,\"ord_no\":\"test-order\"}";
                                    return Mono.just(
                                            ClientResponse.create(HttpStatus.OK)
                                                    .header("Content-Type", "application/json")
                                                    .body(body)
                                                    .build());
                                });
        return new KiwoomUsTradeService(
                properties, auth, mock(KiwoomUsAutoTradeState.class), client);
    }

    private void buy(KiwoomUsTradeService trade, int quantity) {
        try (var limiter = mockStatic(KiwoomApiRateLimiter.class);
                var hours = mockStatic(KiwoomUsMarketHours.class, CALLS_REAL_METHODS)) {
            // Cash authorization is tested independently of the wall-clock trading session.
            hours.when(KiwoomUsMarketHours::isOpen).thenReturn(true);
            hours.when(KiwoomUsMarketHours::isRegularSession).thenReturn(true);
            limiter.when(() -> KiwoomApiRateLimiter.reserveDelayMs(anyLong())).thenReturn(0L);
            trade.placeOrder(
                            new KiwoomUsTradeService.Order(
                                    "BUY", "ND", "TEST", quantity, BigDecimal.TEN, false))
                    .block();
        }
    }

    @Test
    void equityUsesProjectedSettlementCashAndPossessedShares() {
        var cash = deposit().put("won_dfr_amt", "0").put("won_etc_loana", "0");
        for (int day = 1; day <= 4; day++) {
            cash.put("d" + day + "_usd_fx_entr", "800")
                    .put("d" + day + "_usd_buy_excta", day == 1 ? "200" : "0")
                    .put("d" + day + "_usd_sell_excta", "0");
        }
        var balance = json.createObjectNode().put("crnc_code", "USD");
        balance.putArray("result_list")
                .addObject()
                .put("stk_cd", "TEST")
                .put("crnc_code", "USD")
                .put("qty", "0")
                .put("poss_qty", "2")
                .put("now_pric", "100");
        var trade = service(cash, orderability());
        assertEquals(0, new BigDecimal("1000").compareTo(trade.totalAccountEquity(cash, balance)));
        cash.put("d2_usd_fx_entr", "700");
        assertThrows(RuntimeException.class, () -> trade.totalAccountEquity(cash, balance));
        cash.put("d2_usd_fx_entr", "800");
        balance.put("crnc_code", "KRW");
        assertThrows(RuntimeException.class, () -> trade.totalAccountEquity(cash, balance));
        balance.put("crnc_code", "USD");
        cash.remove("d4_usd_fx_entr");
        assertThrows(RuntimeException.class, () -> trade.totalAccountEquity(cash, balance));
    }

    @Test
    void fundsMissingOrMalformedNeverBecomeZeroAuthorization() {
        var trade = service(deposit(), orderability());
        for (String field : List.of("d0_usd_fx_entr", "krw_ord_set_amt")) {
            var missing = deposit();
            missing.remove(field);
            assertThrows(RuntimeException.class, () -> trade.usdCash(missing));
            for (String invalid : List.of("", "bad", "NaN", "-100", "1,2", "1e9")) {
                var value = deposit().put(field, invalid);
                assertThrows(RuntimeException.class, () -> trade.usdCash(value));
            }
        }
    }

    @Test
    void validCashOnlyLimitAllowsExactlyNinetyNinePercent() {
        buy(service(deposit(), orderability()), 99);
        assertEquals("ust20000", requests.getLast());
        assertEquals(3, requests.size());
    }

    @Test
    void brokerAvailableCashAndNoMarginAmountEachLimitOrders() {
        for (String field : List.of("ord_alowa", "min_ord_alowa", "fc_entra")) {
            requests.clear();
            var trade = service(deposit(), orderability().put(field, "50"));
            assertThrows(RuntimeException.class, () -> buy(trade, 10));
            assertFalse(requests.contains("ust20000"));
        }
    }

    @Test
    void debtMissingFieldsAndFractionalOrOverflowQuantityBlockBeforeWrite() {
        for (String field :
                List.of(
                        "fc_uncl_amt",
                        "ord_alowa",
                        "min_ord_alowa",
                        "fc_entra",
                        "krw_ord_set_amt",
                        "krw_ord_alowa_100",
                        "krw_ord_alowq_100")) {
            requests.clear();
            var response = orderability();
            response.remove(field);
            var trade = service(deposit(), response);
            assertThrows(RuntimeException.class, () -> buy(trade, 1));
            assertFalse(requests.contains("ust20000"));
        }
        for (String quantity : List.of("-50", "2.5", "999999999999", "")) {
            requests.clear();
            var trade = service(deposit(), orderability().put("min_ord_alowq", quantity));
            assertThrows(RuntimeException.class, () -> buy(trade, 1));
            assertFalse(requests.contains("ust20000"));
        }
        requests.clear();
        var trade = service(deposit(), orderability().put("fc_uncl_amt", "0.01"));
        assertThrows(RuntimeException.class, () -> buy(trade, 1));
        assertFalse(requests.contains("ust20000"));
    }
}
