package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.service.kiwoom.KiwoomAutoTradeState;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** 키움 국내주식 REST 요청을 한 곳에서 수행합니다. 주문 API는 명시적 설정 없이는 호출되지 않습니다. */
@Service
public class KiwoomTradeService {
    private static final Logger log = LoggerFactory.getLogger(KiwoomTradeService.class);
    private static final long EMPTY_BALANCE_LOG_INTERVAL_MS = 300_000L;
    private final KiwoomProperties properties;
    private final KiwoomAuthService authService;
    private final KiwoomAutoTradeState state;
    private final WebClient webClient;
    private long nextRequestAt;
    private volatile long nextEmptyBalanceLogAt;

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
        return readRequest("kt00001", "/api/dostk/acnt", Map.of("qry_tp", "3"));
    }

    /** 국내주식 계좌평가잔고(kt00018)를 조회합니다. */
    public Mono<JsonNode> getBalance() {
        // qry_tp 2 returns the per-stock rows required for stop-loss/take-profit checks.
        // kt00017 is an account status query and does not include stock holding rows.
        return readRequest(
                        "kt00018", "/api/dostk/acnt", Map.of("qry_tp", "2", "dmst_stex_tp", "KRX"))
                .doOnNext(this::logEmptyBalanceResponse);
    }

    /** 미체결(ka10075) 조회 — 전송한 주문의 상태 동기화용. */
    public Mono<JsonNode> getUnfilledOrders() {
        // all_stk_tp 0:전체 1:종목, trde_tp 0:전체 1:매도 2:매수, stex_tp 0:통합 1:KRX 2:NXT
        return readRequest(
                "ka10075",
                "/api/dostk/acnt",
                Map.of("all_stk_tp", "0", "trde_tp", "0", "stk_cd", "", "stex_tp", "0"));
    }

    /** 매도가능수량 진단 시에만 사용하는 미체결 매도 주문 조회다. */
    public Mono<JsonNode> getUnfilledSellOrders() {
        return readRequest(
                "ka10075",
                "/api/dostk/acnt",
                Map.of("all_stk_tp", "0", "trde_tp", "1", "stk_cd", "", "stex_tp", "0"));
    }

    /** 체결(ka10076) 조회 — 미체결과 파라미터 구성이 다르다(qry_tp/sell_tp). */
    public Mono<JsonNode> getFilledOrders() {
        // qry_tp 0:전체 1:종목, sell_tp 0:전체 1:매도 2:매수, stex_tp 0:통합 1:KRX 2:NXT
        return readRequest(
                "ka10076",
                "/api/dostk/acnt",
                Map.of("stk_cd", "", "qry_tp", "0", "sell_tp", "0", "ord_no", "", "stex_tp", "0"));
    }

    /**
     * 현금(일반) 시장가(3) 또는 지정가(0) 국내 주식 매수/매도 요청입니다. 신용 매수/매도 TR(kt10006/kt10007)은 이 자동매매 서비스에서 절대 사용하지
     * 않습니다.
     */
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
        String apiId;
        String side = order.side() == null ? "" : order.side().toUpperCase(Locale.ROOT);
        if ("BUY".equals(side)) apiId = "kt10000"; // 현금(일반) 매수
        else if ("SELL".equals(side)) apiId = "kt10001"; // 현금(일반) 매도
        else return Mono.error(new IllegalArgumentException("주문 구분은 BUY 또는 SELL만 허용됩니다."));
        Map<String, String> body = new LinkedHashMap<>();
        body.put("dmst_stex_tp", order.exchange() == null ? "KRX" : order.exchange());
        body.put("stk_cd", order.stockCode());
        body.put("ord_qty", String.valueOf(order.quantity()));
        body.put("ord_uv", market ? "" : String.valueOf(order.price()));
        body.put("trde_tp", market ? "3" : "0");
        body.put("cond_uv", "");
        return writeRequest(apiId, "/api/dostk/ordr", body);
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
        return writeRequest("kt10002", "/api/dostk/ordr", body);
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
        return writeRequest("kt10003", "/api/dostk/ordr", body);
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
            // 매도가능수량 0은 유효한 값이다. 보유수량으로 대체하면 이미 다른 주문에
            // 묶였거나 당일 매도 불가인 수량을 다시 매도하려는 주문이 만들어진다.
            // 값이 누락된 응답도 안전하게 0으로 취급해 자동 청산을 보류한다.
            int sellable = (int) number(item, "trde_able_qty");
            out.add(
                    new Holding(
                            code,
                            item.path("stk_nm").asText(code),
                            qty,
                            Math.max(0, sellable),
                            absoluteNumber(item, "pur_pric", "avg_prc", "buy_uv"),
                            absoluteNumber(item, "cur_prc", "prpr"),
                            item.path("prft_rt").asDouble(0)));
        }
        return out;
    }

    /** 매도 거절 때만 사용하는 종목별 잔고 진단 정보다. 계좌번호·인증정보는 포함하지 않는다. */
    public String describeHoldingAvailability(JsonNode balance, String stockCode) {
        if (balance == null || stockCode == null) return "키움 잔고 응답 없음";
        JsonNode arr = balance.path("acnt_evlt_remn_indv_tot");
        if (!arr.isArray()) arr = firstHoldingsArray(balance);
        if (arr == null || !arr.isArray()) return "키움 잔고 종목 배열 없음";
        for (JsonNode item : arr) {
            String code = item.path("stk_cd").asText("").replaceAll("^[A-Za-z]+", "");
            if (!stockCode.equals(code)) continue;
            return "키움잔고[보유="
                    + number(item, "rmnd_qty", "qty")
                    + "주, 매매가능="
                    + number(item, "trde_able_qty")
                    + "주, 금일매수="
                    + number(item, "tdy_buyq")
                    + "주, 금일매도="
                    + number(item, "tdy_sellq")
                    + "주, 신용구분="
                    + item.path("crd_tp_nm").asText(item.path("crd_tp").asText(""))
                    + "]";
        }
        return "키움 잔고에 해당 종목 없음";
    }

    /** 보유수량과 매도가능수량이 다른 경우에만 로그에 남길 원본 잔고 필드다. 계좌번호나 전체 잔고 응답은 포함하지 않는다. */
    public List<SellAvailability> sellAvailabilityDiagnostics(JsonNode balance) {
        List<SellAvailability> result = new ArrayList<>();
        if (balance == null) return result;
        JsonNode arr = balance.path("acnt_evlt_remn_indv_tot");
        if (!arr.isArray()) arr = firstHoldingsArray(balance);
        if (arr == null || !arr.isArray()) return result;
        for (JsonNode item : arr) {
            String code = item.path("stk_cd").asText("").replaceAll("^[A-Za-z]+", "");
            if (!code.matches("\\d{6}")) continue;
            int quantity = (int) number(item, "rmnd_qty", "qty");
            boolean sellableFieldPresent = item.hasNonNull("trde_able_qty");
            String sellableRaw = sellableFieldPresent ? item.path("trde_able_qty").asText("") : "";
            int sellable = (int) number(item, "trde_able_qty");
            if (quantity <= 0 || sellable >= quantity) continue;
            String creditTypeNameRaw = rawText(item, "crd_tp_nm");
            String creditTypeCodeRaw = rawText(item, "crd_tp");
            result.add(
                    new SellAvailability(
                            code,
                            item.path("stk_nm").asText(code),
                            quantity,
                            Math.max(0, sellable),
                            sellableFieldPresent,
                            sellableRaw,
                            (int) number(item, "tdy_buyq"),
                            (int) number(item, "tdy_sellq"),
                            creditTypeNameRaw.isBlank() ? creditTypeCodeRaw : creditTypeNameRaw,
                            item.hasNonNull("crd_tp_nm") || item.hasNonNull("crd_tp"),
                            creditTypeNameRaw,
                            creditTypeCodeRaw,
                            rawText(item, "crd_loan_dt"),
                            fieldNames(item)));
        }
        return result;
    }

    private List<String> fieldNames(JsonNode item) {
        List<String> fields = new ArrayList<>();
        item.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private String rawText(JsonNode item, String field) {
        return item.hasNonNull(field) ? item.path(field).asText("").trim() : "";
    }

    /** kt00018의 총평가금액 — 합산 필드가 없으면 보유 종목의 현재가×수량 합으로 폴백한다. */
    public long totalEvaluationAmount(JsonNode balance) {
        long total = number(balance, "tot_evlt_amt", "evlt_amt");
        if (total > 0) return total;
        long sum = 0;
        for (Holding h : parseHoldings(balance)) sum += h.curPrice() * h.quantity();
        return sum;
    }

    /**
     * 일일 손실 한도용 계좌 총자산을 계산한다.
     *
     * <p>kt00018의 {@code prsm_dpst_aset_amt}(추정예탁자산)는 예수금, 보유 평가금액과 정산 중인 금액을 포함한 계좌 기준 값이므로 최우선으로
     * 사용한다. {@code ord_alow_amt}는 주문 가능 금액일 뿐이라 주문·체결 직후 총자산으로 사용하면 실제 손실이 아닌 변동을 손실로 오인할 수 있다.
     */
    public AccountAsset accountAsset(JsonNode deposit, JsonNode balance) {
        OptionalLong estimatedAsset = parsedNumber(balance, "prsm_dpst_aset_amt");
        if (estimatedAsset.isPresent() && estimatedAsset.getAsLong() > 0)
            return new AccountAsset(estimatedAsset.getAsLong(), "추정예탁자산");

        long cash = number(deposit, "entr", "ord_alow_amt");
        return new AccountAsset(cash + totalEvaluationAmount(balance), "예수금+보유평가금액");
    }

    /** Returns the account evaluation profit/loss, falling back to the per-stock rows. */
    public long totalEvaluationProfitLoss(JsonNode balance) {
        if (balance == null) return 0;
        if (balance.hasNonNull("tot_evlt_pl")) return number(balance, "tot_evlt_pl");
        if (balance.hasNonNull("evlt_pl_amt")) return number(balance, "evlt_pl_amt");
        long sum = 0;
        JsonNode arr = balance.path("acnt_evlt_remn_indv_tot");
        if (!arr.isArray()) arr = firstHoldingsArray(balance);
        if (arr != null && arr.isArray()) {
            for (JsonNode item : arr) sum += number(item, "evltv_prft", "evlt_pl_amt", "pl_amt");
        }
        return sum;
    }

    private JsonNode firstHoldingsArray(JsonNode node) {
        for (JsonNode child : node) {
            if (child.isArray() && child.size() > 0 && child.get(0).has("stk_cd")) return child;
        }
        return null;
    }

    private long number(JsonNode n, String... names) {
        if (n != null)
            for (String x : names) {
                OptionalLong value = parsedNumber(n, x);
                if (value.isPresent()) return value.getAsLong();
            }
        return 0;
    }

    private OptionalLong parsedNumber(JsonNode n, String name) {
        if (n == null || !n.hasNonNull(name)) return OptionalLong.empty();
        String value = n.path(name).asText("").replace(",", "").trim();
        if (value.isEmpty()) return OptionalLong.empty();
        try {
            return OptionalLong.of(Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return OptionalLong.empty();
        }
    }

    private long absoluteNumber(JsonNode n, String... names) {
        return Math.abs(number(n, names));
    }

    private void logEmptyBalanceResponse(JsonNode balance) {
        if (balance == null
                || !parseHoldings(balance).isEmpty()
                || totalEvaluationAmount(balance) > 0) return;
        long now = System.currentTimeMillis();
        if (now < nextEmptyBalanceLogAt) return;
        nextEmptyBalanceLogAt = now + EMPTY_BALANCE_LOG_INTERVAL_MS;

        List<String> fields = new ArrayList<>();
        balance.fields()
                .forEachRemaining(
                        entry -> {
                            JsonNode value = entry.getValue();
                            fields.add(
                                    value.isArray()
                                            ? entry.getKey() + "[" + value.size() + "]"
                                            : entry.getKey());
                        });
        log.info(
                "[자동매매][잔고 조회] 보유 종목 없음 — 키움 응답={}, 응답 필드={}",
                balance.path("return_msg").asText(),
                fields);
    }

    private Mono<JsonNode> readRequest(String apiId, String path, Map<String, ?> body) {
        return request(apiId, path, body, true);
    }

    private Mono<JsonNode> writeRequest(String apiId, String path, Map<String, ?> body) {
        // An order must never be retried automatically: if a response was lost after the
        // exchange accepted it, a retry could create a duplicate order.
        return request(apiId, path, body, false);
    }

    private Mono<JsonNode> request(
            String apiId, String path, Map<String, ?> body, boolean retryInvalidTokenOnce) {
        // 키움 호출 제한은 계정/서비스별로 달라질 수 있습니다. 아래 직렬화 간격은 보수적인 기본 보호막입니다.
        return requestOnce(apiId, path, body, retryInvalidTokenOnce)
                .doOnSuccess(ignored -> state.recordApiSuccess())
                .doOnError(
                        error ->
                                state.recordApiFailure(
                                        apiId + ": " + error.getMessage(),
                                        properties.getMaxConsecutiveApiFailures()));
    }

    private Mono<JsonNode> requestOnce(
            String apiId, String path, Map<String, ?> body, boolean retryInvalidTokenOnce) {
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
                                                    if (retryInvalidTokenOnce
                                                            && isInvalidToken(response)) {
                                                        authService.invalidateAccessToken(token);
                                                        return requestOnce(
                                                                apiId, path, body, false);
                                                    }
                                                    return failOnKiwoomError(apiId, response);
                                                }));
    }

    private boolean isInvalidToken(JsonNode response) {
        return response.path("return_code").asInt(0) == 8005
                || response.path("return_msg").asText("").contains("8005");
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

    public record AccountAsset(long amount, String source) {}

    public record Holding(
            String code,
            String name,
            int quantity,
            int sellable,
            long avgPrice,
            long curPrice,
            double plPct) {}

    public record SellAvailability(
            String stockCode,
            String stockName,
            int remainingQuantity,
            int sellableQuantity,
            boolean sellableFieldPresent,
            String sellableRaw,
            int todayBuyQuantity,
            int todaySellQuantity,
            String creditType,
            boolean creditTypeFieldPresent,
            String creditTypeNameRaw,
            String creditTypeCodeRaw,
            String creditLoanDateRaw,
            List<String> responseFields) {}

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
