package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.service.kiwoom.KiwoomUsAutoTradeState;
import com.hyunchang.webapp.util.KiwoomApiRateLimiter;
import com.hyunchang.webapp.util.KiwoomUsMarketHours;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Kiwoom US REST adapter. Write requests are deliberately never retried. */
@Service
public class KiwoomUsTradeService {
    private static final Logger log = LoggerFactory.getLogger(KiwoomUsTradeService.class);
    public static final String USD_CASH_SOURCE = "D+0 USD 외화예수금(d0_usd_fx_entr)";
    public static final double USD_ONLY_MAX_SPEND_PERCENT = 99.0;
    public static final BigDecimal USD_ONLY_SPEND_RATIO = new BigDecimal("0.99");

    private final KiwoomProperties properties;
    private final KiwoomAuthService authService;
    private final KiwoomUsAutoTradeState state;
    private final WebClient webClient;
    private final Map<String, ExchangeLookup> exchangeCache = new ConcurrentHashMap<>();

    private record ExchangeLookup(LocalDate date, String exchange) {}

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
        return readPages("ust21070", "/api/us/acnt", Map.of("stex_tp", "", "stk_cd", ""), 0)
                .flatMap(this::resolveHoldingExchanges);
    }

    /** ust21070 officially returns country labels such as "미국" in stex_nm. */
    private Mono<JsonNode> resolveHoldingExchanges(JsonNode balance) {
        return Flux.fromIterable(balance.path("result_list"))
                .filter(row -> integer(row, "poss_qty") > 0 && !text(row, "stk_cd").isBlank())
                .concatMap(
                        row -> {
                            String symbol = normalizeSymbol(text(row, "stk_cd"));
                            try {
                                exchangeFrom(row, symbol);
                                return Mono.just(row);
                            } catch (IllegalArgumentException missingExchange) {
                                return lookupExchange(symbol)
                                        .map(
                                                exchange -> {
                                                    ((ObjectNode) row).put("stex_cd", exchange);
                                                    return row;
                                                });
                            }
                        })
                .then(Mono.just(balance));
    }

    private Mono<String> lookupExchange(String symbol) {
        return Mono.defer(
                () -> {
                    LocalDate today = KiwoomUsMarketHours.today();
                    exchangeCache
                            .entrySet()
                            .removeIf(entry -> !entry.getValue().date().equals(today));
                    ExchangeLookup cached = exchangeCache.get(symbol);
                    if (cached != null) return Mono.just(cached.exchange());
                    return read("usa10098", "/api/us/stkinfo", Map.of("stk_cd", symbol))
                            .map(
                                    response -> {
                                        Set<String> exchanges = new HashSet<>();
                                        JsonNode rows = response.path("list");
                                        if (!rows.isArray())
                                            throw new IllegalStateException(
                                                    "거래소 조회 응답 형식 오류: " + symbol);
                                        for (JsonNode row : rows) {
                                            if (!symbol.equalsIgnoreCase(text(row, "stk_cd")))
                                                continue;
                                            // Only an exact symbol and an explicit broker exchange
                                            // code are trusted.
                                            exchanges.add(normalizeExchange(text(row, "stex_tp")));
                                        }
                                        if (exchanges.size() != 1)
                                            throw new IllegalStateException(
                                                    "거래소 조회 결과를 확정할 수 없습니다: " + symbol);
                                        String exchange = exchanges.iterator().next();
                                        exchangeCache.put(
                                                symbol, new ExchangeLookup(today, exchange));
                                        return exchange;
                                    });
                });
    }

    public Mono<JsonNode> getOpenOrders() {
        return readPages(
                "ust21050",
                "/api/us/acnt",
                Map.of("ord_dt", "", "slby_tp", "0", "stex_tp", "", "stk_cd", ""),
                0);
    }

    public Mono<JsonNode> getTodayFills() {
        return readPages(
                "ust21510", "/api/us/acnt", Map.of("slby_tp", "0", "stex_tp", "", "stk_cd", ""), 0);
    }

    public Mono<JsonNode> getOrderHistory(LocalDate from, LocalDate to) {
        return readPages(
                "ust21180",
                "/api/us/acnt",
                Map.of(
                        "strt_dt",
                        from.format(DateTimeFormatter.BASIC_ISO_DATE),
                        "end_dt",
                        to.format(DateTimeFormatter.BASIC_ISO_DATE),
                        "slby_tp",
                        "0",
                        "stex_tp",
                        "",
                        "stk_cd",
                        "",
                        "oppo_trde_tp",
                        "%"),
                0);
    }

    /** Official usa06012: adjusted USD daily OHLC; excludes unfinished ET session. */
    public Mono<List<DailyBar>> getDailyBars(String exchange, String symbol) {
        return readPages(
                        "usa06012",
                        "/api/us/chart",
                        Map.of(
                                "stex_tp",
                                normalizeExchange(exchange),
                                "stk_cd",
                                normalizeSymbol(symbol),
                                "strt_dt",
                                KiwoomUsMarketHours.today()
                                        .format(DateTimeFormatter.BASIC_ISO_DATE),
                                "upd_stkpc_tp",
                                "1",
                                "exrt_appl_tp",
                                "0"),
                        100)
                .map(this::dailyBars);
    }

    List<DailyBar> dailyBars(JsonNode response) {
        Map<LocalDate, DailyBar> bars = new LinkedHashMap<>();
        for (JsonNode row : response.path("result_list")) {
            LocalDate date = LocalDate.parse(text(row, "dt"), DateTimeFormatter.BASIC_ISO_DATE);
            if (!date.isBefore(KiwoomUsMarketHours.today())) continue;
            DailyBar bar =
                    new DailyBar(
                            date,
                            decimal(row, "open_pric").doubleValue(),
                            decimal(row, "high_pric").doubleValue(),
                            decimal(row, "low_pric").doubleValue(),
                            decimal(row, "cur_prc").doubleValue(),
                            decimal(row, "acc_trde_qty").longValue());
            if (!bar.valid()) throw new IllegalStateException("유효하지 않은 미국 일봉: " + date);
            bars.put(date, bar);
        }
        return bars.values().stream().sorted(Comparator.comparing(DailyBar::date)).toList();
    }

    private Mono<JsonNode> readPages(String apiId, String path, Map<String, ?> body, int rowLimit) {
        return Mono.defer(
                        () ->
                                readPages(
                                        apiId,
                                        path,
                                        body,
                                        "",
                                        "",
                                        null,
                                        new HashSet<>(),
                                        0,
                                        rowLimit))
                .doOnSuccess(ignored -> state.recordApiSuccess(apiId))
                .doOnError(
                        error -> {
                            if (!"usa06012".equals(apiId)
                                    && !isTemporaryAccountSettlementError(error))
                                state.recordApiFailure(
                                        apiId,
                                        apiId + ": " + error.getMessage(),
                                        properties.getUs().getMaxConsecutiveApiFailures());
                        });
    }

    private Mono<JsonNode> readPages(
            String apiId,
            String path,
            Map<String, ?> body,
            String continuation,
            String nextKey,
            ObjectNode collected,
            Set<String> keys,
            int page,
            int rowLimit) {
        return requestPage(apiId, path, body, continuation, nextKey, true)
                .flatMap(
                        entity -> {
                            JsonNode response = entity.getBody();
                            // ust21180's published example spells this result_lsit; accept both
                            // contracts.
                            if ("ust21180".equals(apiId)
                                    && response instanceof ObjectNode object
                                    && !response.has("result_list")
                                    && response.path("result_lsit").isArray())
                                object.set("result_list", response.get("result_lsit"));
                            if (response == null || !response.path("result_list").isArray())
                                return Mono.error(
                                        new IllegalStateException("불완전한 목록 응답: " + apiId));
                            ObjectNode result =
                                    collected == null
                                            ? ((ObjectNode) response).deepCopy()
                                            : collected;
                            if (collected != null)
                                result.withArray("result_list")
                                        .addAll(
                                                (com.fasterxml.jackson.databind.node.ArrayNode)
                                                        response.get("result_list"));
                            boolean more = "Y".equalsIgnoreCase(firstHeader(entity, "cont-yn"));
                            if (!more
                                    || (rowLimit > 0
                                            && result.path("result_list").size() >= rowLimit))
                                return Mono.just(result);
                            String key = firstHeader(entity, "next-key");
                            if (key.isBlank() || !keys.add(key) || page >= 19)
                                return Mono.error(new IllegalStateException("연속조회 미완료: " + apiId));
                            return readPages(
                                    apiId, path, body, "Y", key, result, keys, page + 1, rowLimit);
                        });
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
        return readRankPages(body, "", "", true, new ArrayList<>(), 0);
    }

    private Mono<List<RankedStock>> readRankPages(
            Map<String, String> body,
            String continuation,
            String nextKey,
            boolean retryToken,
            List<RankedStock> accumulated,
            int page) {
        return readRankPage(body, continuation, nextKey, retryToken)
                .flatMap(
                        response -> {
                            for (RankedStock stock : rankedStocks(response.body())) {
                                if (accumulated.stream()
                                        .noneMatch(
                                                existing ->
                                                        existing.symbol().equals(stock.symbol()))) {
                                    accumulated.add(stock);
                                }
                            }
                            if (accumulated.size() >= 50
                                    || page >= 4
                                    || !"Y".equalsIgnoreCase(response.continuation())
                                    || response.nextKey().isBlank()) {
                                return Mono.just(
                                        List.copyOf(accumulated.stream().limit(50).toList()));
                            }
                            return readRankPages(
                                    body,
                                    response.continuation(),
                                    response.nextKey(),
                                    true,
                                    accumulated,
                                    page + 1);
                        });
    }

    private Mono<RankPage> readRankPage(
            Map<String, String> body, String continuation, String nextKey, boolean retryToken) {
        String apiId = "usa20540";
        return authService
                .getAccessToken()
                .flatMap(
                        token ->
                                requestDelay()
                                        .then(
                                                webClient
                                                        .post()
                                                        .uri(
                                                                properties.getRestBaseUrl()
                                                                        + "/api/us/rkinfo")
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .header("authorization", "Bearer " + token)
                                                        .header("api-id", apiId)
                                                        .headers(
                                                                headers -> {
                                                                    if (!continuation.isBlank())
                                                                        headers.set(
                                                                                "cont-yn",
                                                                                continuation);
                                                                    if (!nextKey.isBlank())
                                                                        headers.set(
                                                                                "next-key",
                                                                                nextKey);
                                                                })
                                                        .bodyValue(body)
                                                        .retrieve()
                                                        .toEntity(JsonNode.class))
                                        .flatMap(
                                                entity -> {
                                                    JsonNode response = entity.getBody();
                                                    if (response == null)
                                                        return Mono.error(
                                                                new KiwoomApiException(
                                                                        "키움 미국주식 순위 응답이 비어 있습니다."));
                                                    if (retryToken && invalidToken(response)) {
                                                        authService.invalidateAccessToken(token);
                                                        return readRankPage(
                                                                body, continuation, nextKey, false);
                                                    }
                                                    int code =
                                                            response.path("return_code").asInt(0);
                                                    if (code != 0)
                                                        return Mono.error(
                                                                new KiwoomApiException(
                                                                        "키움 미국주식 API 오류("
                                                                                + apiId
                                                                                + "): "
                                                                                + response.path(
                                                                                                "return_msg")
                                                                                        .asText()));
                                                    return Mono.just(
                                                            new RankPage(
                                                                    response,
                                                                    firstHeader(entity, "cont-yn"),
                                                                    firstHeader(
                                                                            entity, "next-key")));
                                                }))
                .doOnSuccess(ignored -> state.recordApiSuccess(apiId))
                .doOnError(
                        error ->
                                state.recordApiFailure(
                                        apiId,
                                        apiId + ": " + error.getMessage(),
                                        properties.getUs().getMaxConsecutiveApiFailures()));
    }

    private String firstHeader(ResponseEntity<?> response, String name) {
        String value = response.getHeaders().getFirst(name);
        return value == null ? "" : value.trim();
    }

    public Mono<JsonNode> getOrderableQuantity(String exchange, String symbol, BigDecimal price) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("stex_tp", normalizeExchange(exchange));
        body.put("stk_cd", normalizeSymbol(symbol));
        body.put("uv", price.stripTrailingZeros().toPlainString());
        return read("ust31490", "/api/us/ordr", body);
    }

    /** 미국주식 현재가 10호가(usa20101)에서 매도·매수 1호가를 조회한다. */
    public Mono<OrderBookQuote> getOrderBook(String exchange, String symbol) {
        String normalizedExchange = normalizeExchange(exchange);
        String normalizedSymbol = normalizeSymbol(symbol);
        Map<String, String> body =
                Map.of(
                        "stex_tp", normalizedExchange,
                        "stk_cd", normalizedSymbol);
        return read("usa20101", "/api/us/mrkcond", body)
                .map(response -> orderBookQuote(response, normalizedExchange, normalizedSymbol))
                .doOnNext(
                        quote ->
                                log.debug(
                                        "[미국자동매매][호가 조회 정상][usa20101] symbol={}, exchange={}, bid={}, ask={}",
                                        normalizedSymbol,
                                        normalizedExchange,
                                        quote.bid(),
                                        quote.ask()))
                .doOnError(
                        error -> {
                            if (error instanceof OrderBookDataException) return;
                            log.warn(
                                    "[미국자동매매][호가 API 조회 실패][usa20101] symbol={}, exchange={}, reason={}",
                                    normalizedSymbol,
                                    normalizedExchange,
                                    error.getMessage());
                        });
    }

    public Mono<KrwOrderServiceStatus> getKrwOrderServiceStatus() {
        return getOrderableQuantity("ND", "AAPL", BigDecimal.ONE)
                .map(
                        response -> {
                            String value = text(response, "krw_ord_rqst_yn").toUpperCase();
                            return switch (value) {
                                case "Y" ->
                                        new KrwOrderServiceStatus(
                                                "APPLIED",
                                                "신청됨",
                                                "원화주문 서비스가 신청되어 있어 자동매매 매수를 차단합니다.");
                                case "N" ->
                                        new KrwOrderServiceStatus(
                                                "CANCELED",
                                                "해지됨",
                                                "원화주문 서비스 해지 상태입니다. 환전된 USD만 사용합니다.");
                                default ->
                                        new KrwOrderServiceStatus(
                                                "UNKNOWN",
                                                "확인 불가",
                                                "원화주문 서비스 상태를 명확히 확인할 수 없어 실제 매수를 차단합니다.");
                            };
                        });
    }

    public Mono<JsonNode> placeOrder(Order order) {
        if (!KiwoomUsMarketHours.isOpen()) {
            return Mono.error(
                    new OrderValidationException("현재는 키움 미국주식 거래 가능 세션이 아닙니다."));
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
        if (order.market() && !KiwoomUsMarketHours.isRegularSession()) {
            return Mono.error(
                    new OrderValidationException("주간·프리·애프터마켓에서는 지정가 주문만 전송할 수 있습니다."));
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
        if (!"BUY".equals(side)) return write(apiId, "/api/us/ordr", body);
        if (order.market()) {
            return Mono.error(new UsdOnlyFundingException("환전된 USD만 사용하도록 미국주식 시장가 매수는 차단합니다."));
        }
        BigDecimal orderNotional = order.price().multiply(BigDecimal.valueOf(order.quantity()));
        Mono<Void> fundingGuard =
                getDepositDetail()
                        .map(this::usdCash)
                        .doOnNext(cash -> requireUsdOnlyFunding(cash, orderNotional))
                        .then(getOrderableQuantity(exchange, symbol, order.price()))
                        .doOnNext(
                                response ->
                                        requireUsdOnlyOrderability(
                                                response, order.quantity(), orderNotional))
                        .onErrorMap(
                                error ->
                                        error instanceof UsdOnlyFundingException
                                                ? error
                                                : new UsdOnlyFundingException(
                                                        "주문 직전 USD 예수금 확인에 실패해 매수를 차단했습니다: "
                                                                + (error.getMessage() == null
                                                                        ? error.getClass()
                                                                                .getSimpleName()
                                                                        : error.getMessage())))
                        .then();
        return fundingGuard.then(write(apiId, "/api/us/ordr", body));
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

    /** Only this field is eligible for buys. Any KRW order setting blocks buys completely. */
    public UsdCash usdCash(JsonNode deposit) {
        BigDecimal available = decimal(deposit, "d0_usd_fx_entr").max(BigDecimal.ZERO);
        BigDecimal krwOrderSetting = decimal(deposit, "krw_ord_set_amt").max(BigDecimal.ZERO);
        boolean usdOnlyBuyAllowed = krwOrderSetting.signum() == 0;
        String blockReason =
                usdOnlyBuyAllowed
                        ? ""
                        : "원화주문설정금이 있어 매수를 차단했습니다. 키움의 미국주식 원화주문 서비스를 해지한 뒤 다시 확인하세요.";
        return new UsdCash(
                available, USD_CASH_SOURCE, krwOrderSetting, usdOnlyBuyAllowed, blockReason);
    }

    public void requireUsdOnlyFunding(UsdCash cash, BigDecimal orderNotional) {
        if (cash == null || !cash.usdOnlyBuyAllowed()) {
            String reason = cash == null ? "USD 예수금 확인 결과가 없습니다." : cash.blockReason();
            throw new UsdOnlyFundingException(reason);
        }
        BigDecimal spendLimit =
                cash.availableUsd().multiply(USD_ONLY_SPEND_RATIO).max(BigDecimal.ZERO);
        if (orderNotional == null
                || orderNotional.signum() <= 0
                || orderNotional.compareTo(spendLimit) > 0) {
            throw new UsdOnlyFundingException(
                    "주문금액이 최신 D+0 USD 외화예수금의 안전한도를 초과해 매수를 차단했습니다." + " 수수료 여유로 USD의 1%를 남깁니다.");
        }
    }

    private void requireUsdOnlyOrderability(
            JsonNode response, int orderQuantity, BigDecimal orderNotional) {
        String krwOrderRequested = text(response, "krw_ord_rqst_yn").toUpperCase();
        if (!"N".equals(krwOrderRequested)) {
            throw new UsdOnlyFundingException(
                    "키움 미국주식 원화주문 서비스가 해지된 상태로 확인되지 않아 매수를 차단했습니다." + " 원화주문 서비스를 해지한 뒤 다시 확인하세요.");
        }
        if (decimal(response, "krw_ord_set_amt").signum() != 0
                || decimal(response, "krw_ord_alowa_100").signum() != 0
                || integer(response, "krw_ord_alowq_100") != 0) {
            throw new UsdOnlyFundingException("키움 주문가능금액에 원화 자금이 포함되어 있어 매수를 차단했습니다.");
        }
        int cashOnlyOrderableQuantity = integer(response, "min_ord_alowq");
        if (cashOnlyOrderableQuantity < orderQuantity) {
            throw new UsdOnlyFundingException("환전된 외화 기준 주문가능수량보다 주문수량이 많아 매수를 차단했습니다.");
        }
        BigDecimal foreignCash = decimal(response, "fc_entra").max(BigDecimal.ZERO);
        if (orderNotional.compareTo(foreignCash.multiply(USD_ONLY_SPEND_RATIO)) > 0) {
            throw new UsdOnlyFundingException("종목별 주문가능수량 조회의 외화예수금 안전한도를 초과해 매수를 차단했습니다.");
        }
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
                            exchangeFrom(row, symbol),
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
                            exchangeFrom(row, symbol),
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

    private OrderBookQuote orderBookQuote(
            JsonNode response, String normalizedExchange, String normalizedSymbol) {
        BigDecimal ask =
                findDecimal(
                        response,
                        // usa20101 공식 응답 필드. 아래 항목은 과거/호환 응답용 별칭이다.
                        "fpr_sel_bid",
                        "sel_1bid",
                        "sel_fpr_bid",
                        "sel_1th_pre_bid",
                        "sel_1th_bid",
                        "sel_bid1",
                        "ask_pric1",
                        "ask_price1");
        BigDecimal bid =
                findDecimal(
                        response,
                        // usa20101 공식 응답 필드. 아래 항목은 과거/호환 응답용 별칭이다.
                        "fpr_buy_bid",
                        "buy_1bid",
                        "buy_fpr_bid",
                        "buy_1th_pre_bid",
                        "buy_1th_bid",
                        "buy_bid1",
                        "bid_pric1",
                        "bid_price1");
        ask = ask.abs();
        bid = bid.abs();
        if (ask.signum() <= 0 || bid.signum() <= 0 || ask.compareTo(bid) < 0) {
            String detail =
                    "symbol="
                            + normalizedSymbol
                            + ", exchange="
                            + normalizedExchange
                            + ", date="
                            + text(response, "dt")
                            + ", bidTime="
                            + text(response, "bid_tm")
                            + ", current="
                            + text(response, "cur_prc")
                            + ", ask="
                            + text(response, "fpr_sel_bid", "sel_1bid")
                            + ", bid="
                            + text(response, "fpr_buy_bid", "buy_1bid")
                            + ", returnMessage="
                            + text(response, "return_msg");
            log.warn(
                    "[미국자동매매][호가 데이터 누락][usa20101] API 정상 응답에서 유효한 최우선 호가를 찾지 못했습니다. {}",
                    detail);
            throw new OrderBookDataException(
                    "키움 호가 API는 정상 응답했지만 유효한 최우선 호가가 없습니다. " + detail);
        }
        return new OrderBookQuote(bid, ask);
    }

    private BigDecimal findDecimal(JsonNode node, String... fields) {
        if (node == null) return BigDecimal.ZERO;
        if (node.isObject()) {
            for (String field : fields) {
                if (node.has(field)) {
                    BigDecimal value = decimal(node, field);
                    if (value.signum() != 0) return value;
                }
            }
            var children = node.elements();
            while (children.hasNext()) {
                BigDecimal value = findDecimal(children.next(), fields);
                if (value.signum() != 0) return value;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                BigDecimal value = findDecimal(child, fields);
                if (value.signum() != 0) return value;
            }
        }
        return BigDecimal.ZERO;
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
                        error -> {
                            if (isTemporaryAccountSettlementError(error)) {
                                log.warn("[미국자동매매][일시적 계좌조회 제한][{}] {}", apiId, error.getMessage());
                                return;
                            }
                            state.recordApiFailure(
                                    apiId,
                                    apiId + ": " + error.getMessage(),
                                    properties.getUs().getMaxConsecutiveApiFailures());
                        });
    }

    private Mono<JsonNode> requestOnce(
            String apiId, String path, Map<String, ?> body, boolean retryToken) {
        return requestPage(apiId, path, body, "", "", retryToken).map(ResponseEntity::getBody);
    }

    private Mono<ResponseEntity<JsonNode>> requestPage(
            String apiId,
            String path,
            Map<String, ?> body,
            String continuation,
            String nextKey,
            boolean retryToken) {
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
                                                        .headers(
                                                                headers -> {
                                                                    if (!continuation.isBlank())
                                                                        headers.set(
                                                                                "cont-yn",
                                                                                continuation);
                                                                    if (!nextKey.isBlank())
                                                                        headers.set(
                                                                                "next-key",
                                                                                nextKey);
                                                                })
                                                        .bodyValue(body)
                                                        .retrieve()
                                                        .toEntity(JsonNode.class))
                                        .flatMap(
                                                entity -> {
                                                    JsonNode response = entity.getBody();
                                                    if (response == null
                                                            || !response.has("return_code"))
                                                        return Mono.error(
                                                                new IllegalStateException(
                                                                        "키움 응답 형식 오류: " + apiId));
                                                    if (retryToken && invalidToken(response)) {
                                                        authService.invalidateAccessToken(token);
                                                        return requestPage(
                                                                apiId,
                                                                path,
                                                                body,
                                                                continuation,
                                                                nextKey,
                                                                false);
                                                    }
                                                    int code =
                                                            response.path("return_code").asInt(0);
                                                    return code == 0
                                                            ? Mono.just(entity)
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

    private Mono<Void> requestDelay() {
        long delay = KiwoomApiRateLimiter.reserveDelayMs(properties.getMinRequestIntervalMs());
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

    /**
     * 키움의 일부 미국 순위·계좌 응답은 {@code stex_tp=미국}처럼 국가명을 먼저 보내고 실제 거래소는 별도 필드에 담는다. 첫 번째 비어 있지 않은 값만 고르면
     * 전체 파싱이 중단되므로, 알려진 거래소 필드를 모두 검사해 ND/NY/NA로 확정한다.
     */
    private String exchangeFrom(JsonNode row, String symbol) {
        String[] fields = {
            "stex_cd",
            "stex_code",
            "stex_nm",
            "ovrs_excg_cd",
            "ovrs_excg_nm",
            "excg_cd",
            "exch_cd",
            "exchange",
            "stex_tp"
        };
        Map<String, String> observed = new LinkedHashMap<>();
        for (String field : fields) {
            String raw = text(row, field);
            if (raw.isBlank()) continue;
            observed.put(field, raw);
            try {
                return normalizeExchange(raw);
            } catch (IllegalArgumentException ignored) {
                // "미국", "US", "0" 같은 국가/전체시장 표시는 건너뛰고 다음 필드를 확인한다.
            }
        }
        throw new IllegalArgumentException(
                "미국주식 거래소를 확인할 수 없습니다: symbol=" + symbol + ", exchangeFields=" + observed);
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
                    || current instanceof UsdOnlyFundingException
                    || current instanceof IllegalArgumentException) return true;
            current = current.getCause();
        }
        return false;
    }

    /** 키움의 일일 결제 처리 시간에만 발생하는 계좌조회 제한. 주문 API 장애로 집계하지 않는다. */
    public static boolean isTemporaryAccountSettlementError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null
                    && (message.contains("572070")
                            || message.contains("수도결제중")
                            || message.contains("수도 결제중"))) return true;
            current = current.getCause();
        }
        return false;
    }

    public static boolean isUsdOnlyFundingBlocked(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof UsdOnlyFundingException) return true;
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

    private static final class OrderBookDataException extends IllegalStateException {
        private OrderBookDataException(String message) {
            super(message);
        }
    }

    private static final class UsdOnlyFundingException extends IllegalStateException {
        private UsdOnlyFundingException(String message) {
            super(message);
        }
    }

    public record UsdCash(
            BigDecimal availableUsd,
            String source,
            BigDecimal krwOrderSettingAmount,
            boolean usdOnlyBuyAllowed,
            String blockReason) {}

    public record KrwOrderServiceStatus(String code, String label, String message) {}

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

        /** 전일 전체 거래량을 현재 장 진행률로 보정한 시간대별 상대 거래량이다. */
        public double relativeVolumeRatio(double regularSessionProgress) {
            if (previousVolume <= 0 || regularSessionProgress <= 0) return 0;
            double expectedSoFar = previousVolume * Math.min(1, regularSessionProgress);
            return expectedSoFar > 0 ? accumulatedVolume / expectedSoFar : 0;
        }
    }

    public record Order(
            String side,
            String exchange,
            String symbol,
            int quantity,
            BigDecimal price,
            boolean market) {}

    public record OrderBookQuote(BigDecimal bid, BigDecimal ask) {
        public double spreadPercent() {
            BigDecimal midpoint =
                    bid.add(ask).divide(BigDecimal.valueOf(2), 8, java.math.RoundingMode.HALF_UP);
            if (midpoint.signum() <= 0) return Double.POSITIVE_INFINITY;
            return ask.subtract(bid)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(midpoint, 8, java.math.RoundingMode.HALF_UP)
                    .doubleValue();
        }
    }

    private record RankPage(JsonNode body, String continuation, String nextKey) {}

    public record DailyBar(
            LocalDate date, double open, double high, double low, double close, long volume) {
        public boolean valid() {
            return date != null
                    && Double.isFinite(open)
                    && Double.isFinite(high)
                    && Double.isFinite(low)
                    && Double.isFinite(close)
                    && low > 0
                    && high >= low
                    && open >= low
                    && open <= high
                    && close >= low
                    && close <= high
                    && volume >= 0;
        }
    }
}
