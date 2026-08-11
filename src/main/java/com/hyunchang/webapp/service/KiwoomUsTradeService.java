package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.service.kiwoom.KiwoomUsAutoTradeState;
import com.hyunchang.webapp.util.KiwoomUsMarketHours;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** Kiwoom US REST adapter. Write requests are deliberately never retried. */
@Service
public class KiwoomUsTradeService {
    public static final String USD_CASH_SOURCE = "D+0 USD 외화예수금(d0_usd_fx_entr)";

    private final KiwoomProperties properties;
    private final KiwoomAuthService authService;
    private final KiwoomUsAutoTradeState state;
    private final WebClient webClient;
    private long nextRequestAt;

    public KiwoomUsTradeService(
            KiwoomProperties properties,
            KiwoomAuthService authService,
            KiwoomUsAutoTradeState state,
            WebClient.Builder builder) {
        this.properties = properties;
        this.authService = authService;
        this.state = state;
        this.webClient = builder.build();
    }

    public Mono<JsonNode> getDepositDetail() {
        return read("ust21160", "/api/us/acnt", Map.of());
    }

    public Mono<JsonNode> getBalance() {
        return read("ust21070", "/api/us/acnt", Map.of("stex_tp", "", "stk_cd", ""));
    }

    public Mono<JsonNode> getOpenOrders() {
        return read(
                "ust21050",
                "/api/us/acnt",
                Map.of("ord_dt", "", "slby_tp", "0", "stex_tp", "", "stk_cd", ""));
    }

    public Mono<JsonNode> getTodayFills() {
        return read(
                "ust21510", "/api/us/acnt", Map.of("slby_tp", "0", "stex_tp", "", "stk_cd", ""));
    }

    public Mono<List<RankedStock>> getTradeValueTop() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("stex_tp", "0");
        body.put("inds_cd", "");
        body.put("stk_tp", "1");
        body.put("trde_qty_tp", "0");
        body.put("stk_cnd", "0");
        body.put("pric_cnd", "0");
        body.put("trde_prica_cnd", "0");
        return read("usa20540", "/api/us/rkinfo", body).map(this::rankedStocks);
    }

    public Mono<JsonNode> placeOrder(Order order) {
        if (!KiwoomUsMarketHours.isOpen()) {
            return Mono.error(
                    new OrderValidationException(
                            "미국주식 자동주문은 미국 정규장(09:30~16:00 ET)에만 전송됩니다."));
        }
        if (!properties.getUs().isTradeEnabled()) {
            return Mono.error(
                    new OrderValidationException(
                            "미국주식 주문 전송이 비활성화되어 있습니다. KIWOOM_US_TRADE_ENABLED=true를 확인하세요."));
        }
        String symbol = normalizeSymbol(order.symbol());
        String exchange = normalizeExchange(order.exchange());
        if (order.quantity() <= 0)
            return Mono.error(new OrderValidationException("주문 수량은 1주 이상이어야 합니다."));
        if (!order.market() && (order.price() == null || order.price().signum() <= 0)) {
            return Mono.error(new OrderValidationException("지정가 주문에는 양수 가격이 필요합니다."));
        }
        String side = order.side() == null ? "" : order.side().toUpperCase();
        String apiId =
                switch (side) {
                    case "BUY" -> "ust20000";
                    case "SELL" -> "ust20001";
                    default -> throw new OrderValidationException("주문 구분은 BUY 또는 SELL이어야 합니다.");
                };
        Map<String, String> body = new LinkedHashMap<>();
        body.put("stex_tp", exchange);
        body.put("stk_cd", symbol);
        body.put("ord_qty", String.valueOf(order.quantity()));
        body.put(
                "ord_uv", order.market() ? "" : order.price().stripTrailingZeros().toPlainString());
        body.put("trde_tp", order.market() ? "03" : "00");
        return write(apiId, "/api/us/ordr", body);
    }

    public Mono<JsonNode> cancelOrder(
            String exchange, String symbol, String orderNo, int quantity) {
        if (orderNo == null || orderNo.isBlank() || quantity <= 0) {
            return Mono.error(new IllegalArgumentException("원주문번호와 취소 수량이 필요합니다."));
        }
        Map<String, String> body = new LinkedHashMap<>();
        body.put("stex_tp", normalizeExchange(exchange));
        body.put("orig_ord_no", orderNo);
        body.put("stk_cd", normalizeSymbol(symbol));
        body.put("cncl_qty", String.valueOf(quantity));
        return write("ust20003", "/api/us/ordr", body);
    }

    /** Only this field is eligible for buys. KRW and KRW-converted order capacity are ignored. */
    public UsdCash usdCash(JsonNode deposit) {
        BigDecimal available = decimal(deposit, "d0_usd_fx_entr").max(BigDecimal.ZERO);
        BigDecimal krwOrderCapacity = decimal(deposit, "krw_ord_set_amt");
        return new UsdCash(available, USD_CASH_SOURCE, krwOrderCapacity, false);
    }

    public List<Holding> holdings(JsonNode response) {
        List<Holding> result = new ArrayList<>();
        JsonNode rows = response == null ? null : response.path("result_list");
        if (rows == null || !rows.isArray()) return result;
        for (JsonNode row : rows) {
            String symbol = text(row, "stk_cd");
            int quantity = integer(row, "poss_qty");
            if (symbol.isBlank() || quantity <= 0) continue;
            result.add(
                    new Holding(
                            normalizeExchange(text(row, "stex_tp", "stex_nm")),
                            symbol,
                            text(row, "frgn_stk_nm", "stk_nm", "stk_enm"),
                            quantity,
                            integer(row, "sell_alowq"),
                            decimal(row, "frgn_stk_book_uv", "avg_pric"),
                            decimal(row, "now_pric", "cur_prc"),
                            decimal(row, "evlt_amt"),
                            decimal(row, "pl_amt"),
                            decimal(row, "pl_rt").doubleValue()));
        }
        return result;
    }

    public BigDecimal totalEvaluation(JsonNode balance) {
        BigDecimal direct = decimal(balance, "tot_evlt_amt");
        if (direct.signum() > 0) return direct;
        return holdings(balance).stream()
                .map(Holding::evaluationAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private List<RankedStock> rankedStocks(JsonNode response) {
        List<RankedStock> result = new ArrayList<>();
        JsonNode rows = response.path("result_list");
        if (!rows.isArray()) return result;
        for (JsonNode row : rows) {
            String symbol = text(row, "stk_cd");
            BigDecimal price = decimal(row, "cur_prc").abs();
            if (symbol.isBlank() || price.signum() <= 0) continue;
            long accumulated = decimal(row, "acc_trde_qty").longValue();
            long previous = decimal(row, "pred_trde_qty").longValue();
            result.add(
                    new RankedStock(
                            integer(row, "rank"),
                            normalizeExchange(text(row, "stex_tp")),
                            symbol,
                            text(row, "stk_nm", "stk_enm"),
                            price,
                            decimal(row, "flu_rt").doubleValue(),
                            accumulated,
                            previous,
                            decimal(row, "trde_prica")));
        }
        return result;
    }

    private Mono<JsonNode> read(String apiId, String path, Map<String, ?> body) {
        return request(apiId, path, body, true);
    }

    private Mono<JsonNode> write(String apiId, String path, Map<String, ?> body) {
        return request(apiId, path, body, false);
    }

    private Mono<JsonNode> request(
            String apiId, String path, Map<String, ?> body, boolean retryToken) {
        return requestOnce(apiId, path, body, retryToken)
                .doOnSuccess(ignored -> state.recordApiSuccess(apiId))
                .doOnError(
                        error ->
                                state.recordApiFailure(
                                        apiId,
                                        apiId + ": " + error.getMessage(),
                                        properties.getUs().getMaxConsecutiveApiFailures()));
    }

    private Mono<JsonNode> requestOnce(
            String apiId, String path, Map<String, ?> body, boolean retryToken) {
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
                                                        .bodyToMono(JsonNode.class))
                                        .flatMap(
                                                response -> {
                                                    if (retryToken && invalidToken(response)) {
                                                        authService.invalidateAccessToken(token);
                                                        return requestOnce(
                                                                apiId, path, body, false);
                                                    }
                                                    int code =
                                                            response.path("return_code").asInt(0);
                                                    return code == 0
                                                            ? Mono.just(response)
                                                            : Mono.error(
                                                                    new KiwoomApiException(
                                                                            "키움 미국주식 API 오류("
                                                                                    + apiId
                                                                                    + "): "
                                                                                    + response.path(
                                                                                                    "return_msg")
                                                                                            .asText()));
                                                }));
    }

    private boolean invalidToken(JsonNode response) {
        return response.path("return_code").asInt(0) == 8005
                || response.path("return_msg").asText("").contains("8005");
    }

    private synchronized Mono<Void> requestDelay() {
        long now = System.currentTimeMillis();
        long delay = Math.max(0, nextRequestAt - now);
        nextRequestAt = Math.max(now, nextRequestAt) + properties.getMinRequestIntervalMs();
        return delay == 0 ? Mono.empty() : Mono.delay(Duration.ofMillis(delay)).then();
    }

    private String normalizeSymbol(String symbol) {
        String value = symbol == null ? "" : symbol.trim().toUpperCase();
        if (!value.matches("[A-Z0-9.\\-]{1,12}"))
            throw new IllegalArgumentException("올바르지 않은 미국주식 심볼입니다.");
        return value;
    }

    private String normalizeExchange(String exchange) {
        String value = exchange == null ? "" : exchange.trim().toUpperCase();
        if (value.contains("NASDAQ")
                || value.contains("나스닥")
                || "NAS".equals(value)
                || "ND".equals(value)) return "ND";
        if (value.contains("NEW YORK")
                || value.contains("NYSE")
                || value.contains("뉴욕")
                || "NYS".equals(value)
                || "NY".equals(value)) return "NY";
        if (value.contains("AMEX")
                || value.contains("AMERICAN")
                || value.contains("아멕스")
                || "AMS".equals(value)
                || "NA".equals(value)) return "NA";
        if (value.matches("[1-3]"))
            return switch (value) {
                case "1" -> "NY";
                case "2" -> "ND";
                default -> "NA";
            };
        throw new IllegalArgumentException("지원하지 않는 미국 거래소 구분입니다: " + value);
    }

    private String text(JsonNode node, String... fields) {
        if (node != null)
            for (String field : fields) {
                String value = node.path(field).asText("").trim();
                if (!value.isBlank()) return value;
            }
        return "";
    }

    private BigDecimal decimal(JsonNode node, String... fields) {
        String value = text(node, fields).replace(",", "").replace("%", "").trim();
        if (value.isBlank()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ignored) {
            return BigDecimal.ZERO;
        }
    }

    private int integer(JsonNode node, String... fields) {
        return decimal(node, fields).abs().intValue();
    }

    public static boolean isDefinitiveOrderFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof KiwoomApiException
                    || current instanceof OrderValidationException
                    || current instanceof IllegalArgumentException) return true;
            current = current.getCause();
        }
        return false;
    }

    private static final class KiwoomApiException extends IllegalStateException {
        private KiwoomApiException(String message) {
            super(message);
        }
    }

    private static final class OrderValidationException extends IllegalStateException {
        private OrderValidationException(String message) {
            super(message);
        }
    }

    public record UsdCash(
            BigDecimal availableUsd,
            String source,
            BigDecimal ignoredKrwOrderCapacity,
            boolean usesKrwConversion) {}

    public record Holding(
            String exchange,
            String symbol,
            String name,
            int quantity,
            int sellableQuantity,
            BigDecimal averagePrice,
            BigDecimal currentPrice,
            BigDecimal evaluationAmount,
            BigDecimal profitLossAmount,
            double profitLossPercent) {}

    public record RankedStock(
            int rank,
            String exchange,
            String symbol,
            String name,
            BigDecimal currentPrice,
            double changePercent,
            long accumulatedVolume,
            long previousVolume,
            BigDecimal tradedValue) {
        public double volumeRatio() {
            return previousVolume > 0 ? accumulatedVolume / (double) previousVolume : 0;
        }
    }

    public record Order(
            String side,
            String exchange,
            String symbol,
            int quantity,
            BigDecimal price,
            boolean market) {}
}
