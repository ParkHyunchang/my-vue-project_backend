package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hyunchang.webapp.config.KiwoomProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** 키움 국내주식 REST 요청을 한 곳에서 수행합니다. 주문 API는 명시적 설정 없이는 호출되지 않습니다. */
@Service
public class KiwoomTradeService {
    private final KiwoomProperties properties;
    private final KiwoomAuthService authService;
    private final KiwoomAutoTradeState state;
    private final WebClient webClient;
    private long nextRequestAt;

    public KiwoomTradeService(
            KiwoomProperties properties,
            KiwoomAuthService authService,
            KiwoomAutoTradeState state,
            WebClient.Builder webClientBuilder) {
        this.properties = properties;
        this.authService = authService;
        this.state = state;
        this.webClient = webClientBuilder.build();
    }

    /** 예수금 상세(kt00001)를 조회합니다. 계좌는 App Key 발급 시 연동된 계좌가 사용됩니다. */
    public Mono<JsonNode> getDeposit() {
        // qry_tp: 3=추정조회, 2=일반조회 (필수 파라미터 — 없으면 return_code=2 에러)
        return request("kt00001", "/api/dostk/acnt", Map.of("qry_tp", "3"));
    }

    /** 국내주식 계좌평가잔고(kt00017)를 조회합니다. */
    public Mono<JsonNode> getBalance() {
        // qry_tp: 1=합산, 2=개별
        return request("kt00017", "/api/dostk/acnt", Map.of("qry_tp", "1", "dmst_stex_tp", "KRX"));
    }

    /** 미체결(ka10075) 조회 — 전송한 주문의 상태 동기화용. */
    public Mono<JsonNode> getUnfilledOrders() {
        // all_stk_tp 0:전체 1:종목, trde_tp 0:전체 1:매도 2:매수, stex_tp 0:통합 1:KRX 2:NXT
        return request(
                "ka10075",
                "/api/dostk/acnt",
                Map.of("all_stk_tp", "0", "trde_tp", "0", "stk_cd", "", "stex_tp", "0"));
    }

    /** 체결(ka10076) 조회 — 미체결과 파라미터 구성이 다르다(qry_tp/sell_tp). */
    public Mono<JsonNode> getFilledOrders() {
        // qry_tp 0:전체 1:종목, sell_tp 0:전체 1:매도 2:매수, stex_tp 0:통합 1:KRX 2:NXT
        return request(
                "ka10076",
                "/api/dostk/acnt",
                Map.of("stk_cd", "", "qry_tp", "0", "sell_tp", "0", "ord_no", "", "stex_tp", "0"));
    }

    /** 시장가(3) 또는 지정가(0) 국내 주식 매수/매도 요청입니다. */
    public Mono<JsonNode> placeOrder(OrderRequest order) {
        if (!properties.isTradeEnabled()) {
            return Mono.error(
                    new IllegalStateException(
                            "주문 전송이 비활성화되어 있습니다. KIWOOM_TRADE_ENABLED=true를 확인하세요."));
        }
        if (order.quantity() <= 0
                || order.stockCode() == null
                || !order.stockCode().matches("\\d{6}")) {
            return Mono.error(new IllegalArgumentException("종목코드(6자리)와 양수 주문수량이 필요합니다."));
        }
        boolean market = "MARKET".equalsIgnoreCase(order.orderType());
        if (!market && (order.price() == null || order.price() <= 0))
            return Mono.error(new IllegalArgumentException("지정가 주문에는 price가 필요합니다."));
        Map<String, String> body = new LinkedHashMap<>();
        body.put("dmst_stex_tp", order.exchange() == null ? "KRX" : order.exchange());
        body.put("stk_cd", order.stockCode());
        body.put("ord_qty", String.valueOf(order.quantity()));
        body.put("ord_uv", market ? "" : String.valueOf(order.price()));
        body.put("trde_tp", market ? "3" : "0");
        body.put("cond_uv", "");
        return request(
                "SELL".equalsIgnoreCase(order.side()) ? "kt10001" : "kt10000",
                "/api/dostk/ordr",
                body);
    }

    /** kt10002(정정): 미체결 주문의 수량·지정가를 변경합니다. */
    public Mono<JsonNode> amendOrder(AmendOrderRequest order) {
        if (!properties.isTradeEnabled())
            return Mono.error(
                    new IllegalStateException(
                            "주문 전송이 비활성화되어 있습니다. KIWOOM_TRADE_ENABLED=true를 확인하세요."));
        if (order.originalOrderNo() == null
                || order.originalOrderNo().isBlank()
                || order.quantity() <= 0
                || order.price() == null
                || order.price() <= 0)
            return Mono.error(new IllegalArgumentException("원주문번호, 정정 수량, 지정가가 모두 필요합니다."));
        Map<String, String> body = new LinkedHashMap<>();
        body.put("dmst_stex_tp", "KRX");
        body.put("orig_ord_no", order.originalOrderNo());
        body.put("stk_cd", order.stockCode());
        body.put("mdfy_qty", String.valueOf(order.quantity()));
        body.put("mdfy_uv", String.valueOf(order.price()));
        body.put("mdfy_cond_uv", "");
        return request("kt10002", "/api/dostk/ordr", body);
    }

    /** kt10003(취소): 미체결 주문의 잔량 일부/전부를 취소합니다. */
    public Mono<JsonNode> cancelOrder(CancelOrderRequest order) {
        if (order.originalOrderNo() == null
                || order.originalOrderNo().isBlank()
                || order.quantity() <= 0)
            return Mono.error(new IllegalArgumentException("원주문번호와 취소 수량이 필요합니다."));
        Map<String, String> body = new LinkedHashMap<>();
        body.put("dmst_stex_tp", "KRX");
        body.put("orig_ord_no", order.originalOrderNo());
        body.put("stk_cd", order.stockCode());
        body.put("cncl_qty", String.valueOf(order.quantity()));
        return request("kt10003", "/api/dostk/ordr", body);
    }

    /** kt00018 계좌평가잔고의 종목 배열을 파싱한다. 필드명은 실측으로 확정되지 않아 대체 이름을 함께 시도한다. */
    public List<Holding> parseHoldings(JsonNode balance) {
        List<Holding> out = new ArrayList<>();
        if (balance == null) return out;
        JsonNode arr = balance.path("acnt_evlt_remn_indv_tot");
        if (!arr.isArray()) arr = firstHoldingsArray(balance);
        if (arr == null || !arr.isArray()) return out;
        for (JsonNode item : arr) {
            // kt00018 종목코드는 A005930 처럼 접두 문자가 붙는다.
            String code = item.path("stk_cd").asText("").replaceAll("^[A-Za-z]+", "");
            if (!code.matches("\\d{6}")) continue;
            int qty = (int) number(item, "rmnd_qty", "qty");
            if (qty <= 0) continue;
            int sellable = (int) number(item, "trde_able_qty", "rmnd_qty");
            out.add(
                    new Holding(
                            code,
                            item.path("stk_nm").asText(code),
                            qty,
                            sellable > 0 ? sellable : qty,
                            number(item, "pur_pric", "avg_prc"),
                            number(item, "cur_prc", "prpr"),
                            item.path("prft_rt").asDouble(0)));
        }
        return out;
    }

    /** kt00018의 총평가금액 — 합산 필드가 없으면 보유 종목의 현재가×수량 합으로 폴백한다. */
    public long totalEvaluationAmount(JsonNode balance) {
        long total = number(balance, "tot_evlt_amt", "evlt_amt");
        if (total > 0) return total;
        long sum = 0;
        for (Holding h : parseHoldings(balance)) sum += h.curPrice() * h.quantity();
        return sum;
    }

    private JsonNode firstHoldingsArray(JsonNode node) {
        for (JsonNode child : node) {
            if (child.isArray() && child.size() > 0 && child.get(0).has("stk_cd")) return child;
        }
        return null;
    }

    private long number(JsonNode n, String... names) {
        if (n != null) for (String x : names) if (n.has(x)) return n.path(x).asLong();
        return 0;
    }

    private Mono<JsonNode> request(String apiId, String path, Map<String, ?> body) {
        // 키움 호출 제한은 계정/서비스별로 달라질 수 있습니다. 아래 직렬화 간격은 보수적인 기본 보호막입니다.
        return authService
                .getAccessToken()
                .flatMap(
                        token ->
                                requestDelay()
                                        .then(
                                                webClient
                                                        .post()
                                                        .uri(properties.getRestBaseUrl() + path)
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .header("authorization", "Bearer " + token)
                                                        .header("api-id", apiId)
                                                        .bodyValue(body)
                                                        .retrieve()
                                                        .bodyToMono(JsonNode.class)))
                .flatMap(response -> failOnKiwoomError(apiId, response))
                .doOnSuccess(ignored -> state.recordApiSuccess())
                .doOnError(
                        error ->
                                state.recordApiFailure(
                                        apiId + ": " + error.getMessage(),
                                        properties.getMaxConsecutiveApiFailures()));
    }

    /** 키움 오류 응답(return_code != 0)을 조용히 0원으로 파싱하지 않도록 명시적 에러로 변환합니다. */
    private Mono<JsonNode> failOnKiwoomError(String apiId, JsonNode response) {
        if (response.path("return_code").asInt(0) != 0) {
            return Mono.error(
                    new IllegalStateException(
                            "키움 API 오류(" + apiId + "): " + response.path("return_msg").asText()));
        }
        return Mono.just(response);
    }

    private synchronized Mono<Void> requestDelay() {
        long now = System.currentTimeMillis();
        long delay = Math.max(0, nextRequestAt - now);
        nextRequestAt = Math.max(now, nextRequestAt) + properties.getMinRequestIntervalMs();
        return delay == 0 ? Mono.empty() : Mono.delay(Duration.ofMillis(delay)).then();
    }

    public record Holding(
            String code,
            String name,
            int quantity,
            int sellable,
            long avgPrice,
            long curPrice,
            double plPct) {}

    public record OrderRequest(
            String side,
            String stockCode,
            int quantity,
            Long price,
            String orderType,
            String exchange) {}

    public record AmendOrderRequest(
            String originalOrderNo, String stockCode, int quantity, Long price) {}

    public record CancelOrderRequest(String originalOrderNo, String stockCode, int quantity) {}
}
