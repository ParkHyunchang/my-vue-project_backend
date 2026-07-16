package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hyunchang.webapp.config.KiwoomProperties;
import java.time.Duration;
import java.util.LinkedHashMap;
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
    private final WebClient webClient;
    private long nextRequestAt;

    public KiwoomTradeService(
            KiwoomProperties properties,
            KiwoomAuthService authService,
            WebClient.Builder webClientBuilder) {
        this.properties = properties;
        this.authService = authService;
        this.webClient = webClientBuilder.build();
    }

    /** 예수금 상세(kt00001)를 조회합니다. 계좌는 App Key 발급 시 연동된 계좌가 사용됩니다. */
    public Mono<JsonNode> getDeposit() {
        // qry_tp: 3=추정조회, 2=일반조회 (필수 파라미터 — 없으면 return_code=2 에러)
        return request("kt00001", "/api/dostk/acnt", Map.of("qry_tp", "3"));
    }

    /** 계좌평가잔고(kt00018)를 조회합니다. */
    public Mono<JsonNode> getBalance() {
        // qry_tp: 1=합산, 2=개별
        return request("kt00018", "/api/dostk/acnt", Map.of("qry_tp", "1", "dmst_stex_tp", "KRX"));
    }

    /** 미체결(ka10075) 조회 — 전송한 주문의 상태 동기화용. 필드값은 모의 모드에서 실측 확인 필요. */
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

    /** kt10002(정정): 미체결 주문의 수량·지정가를 변경합니다. 필드명은 모의 모드에서 실측 확인 필요. */
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
                .flatMap(response -> failOnKiwoomError(apiId, response));
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
