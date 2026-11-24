package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hyunchang.webapp.util.KiwoomUsMarketHours;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.test.util.ReflectionTestUtils;

/** Test-only USD cash account. Orders cause fills; supplied frames never supply account state. */
final class KiwoomUsReplayLedger {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal SPEND_RATIO = new BigDecimal("0.99");
    private final BigDecimal initialCash;
    private final BigDecimal feeBps;
    private final JsonNode initialHoldings;
    private BigDecimal cash;
    private BigDecimal initialEquity;
    private Instant now;
    private String frameId;
    private final Set<String> frameIds = new HashSet<>();
    private final Map<String, Position> positions = new LinkedHashMap<>();
    private final Map<String, Order> orders = new LinkedHashMap<>();
    private Map<String, Quote> quotes = new LinkedHashMap<>();
    private final Map<String, Quote> lastObservations = new LinkedHashMap<>();
    private final List<Receivable> receivables = new ArrayList<>();
    private final ArrayNode events = JSON.createArrayNode();
    private final ArrayNode executions = JSON.createArrayNode();
    private long nextOrder = 1;
    private long nextFill = 1;

    KiwoomUsReplayLedger(JsonNode initial, JsonNode model) {
        exactKeys(initial, Set.of("cash_usd", "holdings"));
        exactKeys(model, Set.of("fee_bps", "settlement"));
        initialCash = nonnegative(initial, "cash_usd");
        cash = initialCash;
        feeBps = nonnegative(model, "fee_bps");
        if (feeBps.compareTo(new BigDecimal("100")) > 0)
            throw new IllegalArgumentException("fee_bps must be between 0 and 100");
        if (!"NEXT_TRADING_DAY".equals(model.path("settlement").asText()))
            throw new IllegalArgumentException(
                    "Only NEXT_TRADING_DAY sell settlement is supported");
        if (!initial.path("holdings").isArray())
            throw new IllegalArgumentException("holdings must be an array");
        initialHoldings = initial.path("holdings").deepCopy();
        for (JsonNode row : initialHoldings) {
            exactKeys(row, Set.of("symbol", "exchange", "quantity", "average_price"));
            String symbol = symbol(row.path("symbol").asText());
            String exchange = exchange(row.path("exchange").asText());
            int quantity = quantity(row, "quantity", false);
            BigDecimal price = positive(row, "average_price");
            if (positions.put(
                            symbol,
                            new Position(
                                    exchange,
                                    quantity,
                                    price.multiply(BigDecimal.valueOf(quantity))))
                    != null) throw new IllegalArgumentException("Duplicate initial holding");
        }
    }

    void advanceFrame(JsonNode frame) {
        Instant at = Instant.parse(frame.path("at").asText());
        String id = frame.path("id").asText();
        if (id.isBlank() || frameIds.contains(id) || now != null && !at.isAfter(now))
            throw new IllegalArgumentException(
                    "Frames must have unique ids and strictly increasing times");
        int year = at.atZone(KiwoomUsMarketHours.ET).getYear();
        if (year < 2026 || year > 2028)
            throw new IllegalArgumentException("Unsupported market calendar year");
        if (!frame.path("quotes").isObject()) throw new IllegalArgumentException("Quotes required");
        Map<String, Quote> incoming = new LinkedHashMap<>();
        frame.path("quotes")
                .fields()
                .forEachRemaining(
                        entry -> {
                            JsonNode q = entry.getValue();
                            BigDecimal bid = positive(q, "bid"), ask = positive(q, "ask");
                            Instant available = Instant.parse(q.path("available_at").asText());
                            if (ask.compareTo(bid) < 0
                                    || available.isAfter(at)
                                    || Duration.between(available, at)
                                                    .compareTo(Duration.ofSeconds(90))
                                            > 0)
                                throw new IllegalArgumentException(
                                        "Invalid, future or stale quote");
                            String name = symbol(entry.getKey());
                            Quote value =
                                    new Quote(
                                            bid,
                                            ask,
                                            available,
                                            quantity(q, "bid_size", true),
                                            quantity(q, "ask_size", true));
                            Quote previous = lastObservations.get(name);
                            if (previous != null) {
                                if (available.isBefore(previous.at))
                                    throw new IllegalArgumentException(
                                            "Quote time moved backwards");
                                if (available.equals(previous.at)) {
                                    if (bid.compareTo(previous.bid) != 0
                                            || ask.compareTo(previous.ask) != 0
                                            || value.bidSize != previous.bidSize
                                            || value.askSize != previous.askSize)
                                        throw new IllegalArgumentException(
                                                "Same observation has conflicting values");
                                    value.bidRemaining = previous.bidRemaining;
                                    value.askRemaining = previous.askRemaining;
                                }
                            }
                            incoming.put(name, value);
                        });
        Set<String> required = new HashSet<>(positions.keySet());
        for (Order order : orders.values()) if (order.remaining() > 0) required.add(order.symbol);
        if (!incoming.keySet().containsAll(required))
            throw new IllegalArgumentException("All holdings and pending orders require quotes");
        // Validate the entire frame before changing cash, settlement or fills.
        now = at;
        frameId = id;
        frameIds.add(id);
        quotes = incoming;
        lastObservations.putAll(incoming);
        Iterator<Receivable> due = receivables.iterator();
        while (due.hasNext()) {
            Receivable item = due.next();
            if (!now.isBefore(item.at)) {
                cash = cash.add(item.amount);
                event("SETTLEMENT").put("fill_id", item.fillId).put("amount", item.amount);
                due.remove();
            }
        }
        KiwoomUsMarketHours.Session session =
                ReflectionTestUtils.invokeMethod(
                        KiwoomUsMarketHours.class,
                        "sessionAt",
                        LocalDateTime.ofInstant(now, KiwoomUsMarketHours.ET));
        if (session == KiwoomUsMarketHours.Session.REGULAR) {
            for (Order order : orders.values()) {
                if (order.remaining() == 0 || !now.isAfter(order.at)) continue;
                Quote quote = quotes.get(order.symbol);
                if (!quote.at.isAfter(order.at)) continue;
                KiwoomUsMarketHours.Session quoteSession =
                        ReflectionTestUtils.invokeMethod(
                                KiwoomUsMarketHours.class,
                                "sessionAt",
                                LocalDateTime.ofInstant(quote.at, KiwoomUsMarketHours.ET));
                if (quoteSession != KiwoomUsMarketHours.Session.REGULAR) continue;
                BigDecimal price = order.buy ? quote.ask : quote.bid;
                if (!order.market
                        && (order.buy
                                ? price.compareTo(order.limit) > 0
                                : price.compareTo(order.limit) < 0)) continue;
                int size =
                        Math.min(
                                order.remaining(),
                                order.buy ? quote.askRemaining : quote.bidRemaining);
                if (size == 0) continue;
                fill(order, quote, size, price);
                if (order.buy) quote.askRemaining -= size;
                else quote.bidRemaining -= size;
            }
        }
        if (initialEquity == null) initialEquity = equity();
        invariant();
        ObjectNode mark = event("MARK");
        mark.setAll(state());
        ObjectNode marks = mark.putObject("quotes");
        quotes.forEach(
                (name, quote) ->
                        marks.putObject(name)
                                .put("bid", quote.bid)
                                .put("ask", quote.ask)
                                .put("available_at", quote.at.toString())
                                .put("bid_size", quote.bidSize)
                                .put("ask_size", quote.askSize));
    }

    BigDecimal initialEquity() {
        requireFrame();
        return initialEquity;
    }

    ObjectNode submit(
            String side,
            String exchange,
            String symbol,
            int quantity,
            BigDecimal limit,
            boolean market) {
        requireFrame();
        symbol = symbol(symbol);
        exchange = exchange(exchange);
        if ((!"BUY".equals(side) && !"SELL".equals(side))
                || quantity <= 0
                || (!market && (limit == null || limit.signum() <= 0)))
            throw new IllegalArgumentException("Invalid order");
        boolean buy = "BUY".equals(side);
        if (market && buy) throw new IllegalArgumentException("Market buys are unsupported");
        if (!quotes.containsKey(symbol))
            throw new IllegalArgumentException("Order has no current quote");
        BigDecimal notional = market ? ZERO : limit.multiply(BigDecimal.valueOf(quantity));
        if (buy
                && (notional.compareTo(availableCash().multiply(SPEND_RATIO)) > 0
                        || notional.add(fee(notional)).compareTo(availableCash()) > 0))
            return rejection("Insufficient cash including reserved orders and fees");
        Position holding = positions.get(symbol);
        if (!buy
                && (holding == null
                        || !holding.exchange.equals(exchange)
                        || quantity > sellable(symbol)))
            return rejection("Insufficient unreserved shares");
        if (buy && holding != null && !holding.exchange.equals(exchange))
            throw new IllegalArgumentException("Exchange differs from existing holding");
        long pendingBuys = 0;
        for (Order pending : orders.values())
            if (pending.buy && pending.symbol.equals(symbol)) pendingBuys += pending.remaining();
        if (buy
                && (long) (holding == null ? 0 : holding.quantity) + pendingBuys + quantity
                        > Integer.MAX_VALUE)
            return rejection("Holding quantity exceeds supported integer range");
        String no = "SIM" + nextOrder++;
        Order order =
                new Order(no, side, exchange, symbol, quantity, market ? ZERO : limit, market, now);
        orders.put(no, order);
        event("ORDER")
                .put("order_id", no)
                .put("side", side)
                .put("exchange", exchange)
                .put("symbol", symbol)
                .put("quantity", quantity)
                .put("limit_price", order.limit)
                .put("market", market)
                .put("reserved_cash", reserve(order));
        invariant();
        return success(no);
    }

    ObjectNode cancel(String exchange, String symbol, String orderNo, int quantity) {
        requireFrame();
        Order order = orders.get(orderNo);
        if (order == null
                || !order.exchange.equals(exchange(exchange))
                || !order.symbol.equals(symbol(symbol))
                || quantity <= 0
                || quantity > order.remaining())
            return rejection("Unknown order or invalid cancel quantity");
        order.cancelled += quantity;
        event("CANCEL").put("order_id", orderNo).put("quantity", quantity);
        invariant();
        return success(orderNo);
    }

    private void fill(Order order, Quote quote, int quantity, BigDecimal price) {
        BigDecimal gross = price.multiply(BigDecimal.valueOf(quantity));
        BigDecimal fee = fee(gross);
        Instant settlement = order.buy ? null : nextSettlement(now);
        String fillId = "FILL" + nextFill++;
        ObjectNode e =
                event("FILL")
                        .put("fill_id", fillId)
                        .put("order_id", order.no)
                        .put("side", order.buy ? "BUY" : "SELL")
                        .put("symbol", order.symbol)
                        .put("exchange", order.exchange)
                        .put("quantity", quantity)
                        .put("price", price)
                        .put("fee", fee)
                        .put("quote_at", quote.at.toString())
                        .put("bid", quote.bid)
                        .put("ask", quote.ask)
                        .put("bid_size", quote.bidSize)
                        .put("ask_size", quote.askSize);
        if (order.buy) {
            cash = cash.subtract(gross.add(fee));
            Position p =
                    positions.computeIfAbsent(
                            order.symbol, ignored -> new Position(order.exchange, 0, ZERO));
            p.quantity += quantity;
            p.cost = p.cost.add(gross).add(fee);
        } else {
            Position p = positions.get(order.symbol);
            BigDecimal releasedCost =
                    p.cost.multiply(BigDecimal.valueOf(quantity))
                            .divide(BigDecimal.valueOf(p.quantity), 12, RoundingMode.HALF_UP);
            p.cost = p.cost.subtract(releasedCost);
            p.quantity -= quantity;
            if (p.quantity == 0) positions.remove(order.symbol);
            receivables.add(new Receivable(fillId, gross.subtract(fee), settlement));
            e.put("settles_at", settlement.toString())
                    .put("realized_pnl", gross.subtract(fee).subtract(releasedCost));
        }
        order.filled += quantity;
        order.fillNotional = order.fillNotional.add(gross);
        executions.add(e.deepCopy());
        invariant();
    }

    ObjectNode deposit() {
        requireFrame();
        ObjectNode d =
                JSON.createObjectNode()
                        .put("return_code", 0)
                        .put("d0_usd_fx_entr", cash)
                        .put("krw_ord_set_amt", 0)
                        .put("won_dfr_amt", 0)
                        .put("won_etc_loana", 0);
        BigDecimal receivable = unsettled();
        for (int i = 1; i <= 4; i++) {
            d.put("d" + i + "_usd_fx_entr", cash.add(receivable))
                    .put("d" + i + "_usd_sell_excta", i == 1 ? receivable : ZERO)
                    .put("d" + i + "_usd_buy_excta", ZERO);
        }
        return d;
    }

    ObjectNode balance() {
        requireFrame();
        ObjectNode b = JSON.createObjectNode().put("return_code", 0).put("crnc_code", "USD");
        ArrayNode rows = b.putArray("result_list");
        BigDecimal total = ZERO;
        for (var entry : positions.entrySet()) {
            String symbol = entry.getKey();
            Position p = entry.getValue();
            BigDecimal bid = quotes.get(symbol).bid;
            BigDecimal valuation = bid.multiply(BigDecimal.valueOf(p.quantity));
            total = total.add(valuation);
            rows.addObject()
                    .put("stk_cd", symbol)
                    .put("stex_cd", p.exchange)
                    .put("crnc_code", "USD")
                    .put("frgn_stk_nm", "Simulated " + symbol)
                    .put("poss_qty", p.quantity)
                    .put("sell_alowq", sellable(symbol))
                    .put("avg_pric", p.average())
                    .put("now_pric", bid)
                    .put("evlt_amt", valuation)
                    .put("pl_amt", valuation.subtract(p.cost))
                    .put(
                            "pl_rt",
                            valuation
                                    .subtract(p.cost)
                                    .multiply(new BigDecimal("100"))
                                    .divide(p.cost, 12, RoundingMode.HALF_UP));
        }
        return b.put("tot_evlt_amt", total);
    }

    ObjectNode orderability(String symbol, BigDecimal price) {
        requireFrame();
        symbol(symbol);
        if (price == null || price.signum() <= 0)
            throw new IllegalArgumentException("Positive order price required");
        BigDecimal free = availableCash();
        int quantity =
                free.divide(price.add(fee(price)), 0, RoundingMode.DOWN)
                        .min(BigDecimal.valueOf(Integer.MAX_VALUE))
                        .intValueExact();
        return JSON.createObjectNode()
                .put("return_code", 0)
                .put("krw_ord_rqst_yn", "N")
                .put("krw_ord_set_amt", 0)
                .put("krw_ord_alowa_100", 0)
                .put("krw_ord_alowq_100", 0)
                .put("min_ord_alowq", quantity)
                .put("fc_entra", free)
                .put("fc_uncl_amt", 0)
                .put("ord_alowa", free)
                .put("min_ord_alowa", free);
    }

    ObjectNode openOrders() {
        return orderRows(0);
    }

    ObjectNode fills() {
        return orderRows(1);
    }

    ObjectNode history() {
        return orderRows(2);
    }

    private ObjectNode orderRows(int kind) {
        requireFrame();
        ObjectNode result = JSON.createObjectNode().put("return_code", 0);
        ArrayNode rows = result.putArray("result_list");
        for (Order o : orders.values()) {
            if (kind == 0 && o.remaining() == 0 || kind == 1 && o.filled == 0 && o.cancelled == 0)
                continue;
            rows.addObject()
                    .put("ord_no", o.no)
                    .put(
                            "ord_dt",
                            o.at.atZone(KiwoomUsMarketHours.ET)
                                    .toLocalDate()
                                    .format(DateTimeFormatter.BASIC_ISO_DATE))
                    .put("stk_cd", o.symbol)
                    .put("stex_cd", o.exchange)
                    .put("slby_tp", o.buy ? "2" : "1")
                    .put("ord_qty", o.quantity)
                    .put("cntr_qty", o.filled)
                    .put("oso_qty", o.remaining())
                    .put("cncl_qty", o.cancelled)
                    .put("ord_uv", o.limit)
                    .put(
                            "cntr_uv",
                            o.filled == 0
                                    ? ZERO
                                    : o.fillNotional.divide(
                                            BigDecimal.valueOf(o.filled), 12, RoundingMode.HALF_UP))
                    .put(
                            "ord_stat",
                            o.remaining() > 0 ? "OPEN" : o.cancelled > 0 ? "CANCELED" : "FILLED");
        }
        return result;
    }

    ObjectNode report() {
        requireFrame();
        ObjectNode r =
                state().put("initial_cash_usd", initialCash)
                        .put("initial_equity_usd", initialEquity)
                        .put("fee_bps", feeBps)
                        .put("settlement", "NEXT_TRADING_DAY")
                        .put("valuation", "BID")
                        .put("fill_policy", "REGULAR_SESSION_NEXT_FRAME_QUOTE")
                        .put("performance_verified", false)
                        .put("real_order_count", 0);
        r.set("initial_holdings", initialHoldings.deepCopy());
        r.set("orders", history().path("result_list"));
        r.set("fills", executions.deepCopy());
        r.set("events", events.deepCopy());
        return r;
    }

    ObjectNode snapshot() {
        requireFrame();
        return state();
    }

    private ObjectNode state() {
        ObjectNode r =
                JSON.createObjectNode()
                        .put("cash_usd", cash)
                        .put("available_cash_usd", availableCash())
                        .put("reserved_cash_usd", reserved())
                        .put("unsettled_cash_usd", unsettled())
                        .put("equity_usd", equity());
        ArrayNode rows = r.putArray("holdings");
        positions.forEach(
                (symbol, p) ->
                        rows.addObject()
                                .put("symbol", symbol)
                                .put("exchange", p.exchange)
                                .put("quantity", p.quantity)
                                .put("average_price", p.average())
                                .put("cost_usd", p.cost)
                                .put("price", quotes.get(symbol).bid));
        return r;
    }

    private BigDecimal equity() {
        BigDecimal result = cash.add(unsettled());
        for (var entry : positions.entrySet())
            result =
                    result.add(
                            quotes.get(entry.getKey())
                                    .bid
                                    .multiply(BigDecimal.valueOf(entry.getValue().quantity)));
        return result;
    }

    private BigDecimal unsettled() {
        return receivables.stream().map(r -> r.amount).reduce(ZERO, BigDecimal::add);
    }

    private BigDecimal reserved() {
        return orders.values().stream().map(this::reserve).reduce(ZERO, BigDecimal::add);
    }

    private BigDecimal availableCash() {
        return cash.subtract(reserved());
    }

    private BigDecimal reserve(Order o) {
        BigDecimal amount = o.buy ? o.limit.multiply(BigDecimal.valueOf(o.remaining())) : ZERO;
        return amount.add(fee(amount));
    }

    private BigDecimal fee(BigDecimal gross) {
        return gross.multiply(feeBps).divide(new BigDecimal("10000"));
    }

    private int sellable(String symbol) {
        Position p = positions.get(symbol);
        if (p == null) return 0;
        int reserved =
                orders.values().stream()
                        .filter(o -> !o.buy && o.symbol.equals(symbol))
                        .mapToInt(Order::remaining)
                        .sum();
        return p.quantity - reserved;
    }

    private void invariant() {
        if (cash.signum() < 0 || availableCash().signum() < 0 || unsettled().signum() < 0)
            throw new AssertionError("Negative cash or reservation overdraw");
        for (var p : positions.entrySet())
            if (p.getValue().quantity <= 0
                    || p.getValue().cost.signum() <= 0
                    || sellable(p.getKey()) < 0)
                throw new AssertionError("Invalid holding or oversell");
    }

    private ObjectNode event(String type) {
        return events.addObject()
                .put("event_seq", events.size())
                .put("frame", frameId)
                .put("at", now.toString())
                .put("type", type);
    }

    private ObjectNode rejection(String reason) {
        event("REJECT").put("reason", reason);
        return JSON.createObjectNode().put("return_code", 1).put("return_msg", reason);
    }

    private ObjectNode success(String no) {
        return JSON.createObjectNode().put("return_code", 0).put("ord_no", no);
    }

    private void requireFrame() {
        if (now == null) throw new IllegalStateException("Advance an initial market frame first");
    }

    private static LocalDate tradeDate(Instant at) {
        var et = at.atZone(KiwoomUsMarketHours.ET);
        return et.toLocalTime().isBefore(LocalTime.of(20, 0))
                ? et.toLocalDate()
                : et.toLocalDate().plusDays(1);
    }

    private static Instant nextSettlement(Instant at) {
        LocalDate date = tradeDate(at);
        for (int i = 0; i < 14; i++) {
            date = date.plusDays(1);
            if (KiwoomUsMarketHours.isTradingDay(date))
                return date.atTime(9, 30).atZone(KiwoomUsMarketHours.ET).toInstant();
        }
        throw new IllegalArgumentException("Unsupported settlement calendar");
    }

    private static String symbol(String s) {
        if (s == null || !s.matches("[A-Z][A-Z0-9.]{0,11}"))
            throw new IllegalArgumentException("Invalid symbol");
        return s;
    }

    private static String exchange(String s) {
        if (!Set.of("ND", "NY", "AM").contains(s))
            throw new IllegalArgumentException("Unsupported exchange");
        return s;
    }

    private static BigDecimal nonnegative(JsonNode row, String field) {
        if (!row.path(field).isNumber())
            throw new IllegalArgumentException("Numeric " + field + " required");
        BigDecimal n = row.path(field).decimalValue();
        if (n.signum() < 0) throw new IllegalArgumentException("Negative " + field);
        return n;
    }

    private static BigDecimal positive(JsonNode row, String field) {
        BigDecimal n = nonnegative(row, field);
        if (n.signum() <= 0) throw new IllegalArgumentException("Positive " + field + " required");
        return n;
    }

    private static int quantity(JsonNode row, String field, boolean zero) {
        if (!row.path(field).isIntegralNumber() || !row.path(field).canConvertToInt())
            throw new IllegalArgumentException("Integer " + field + " required");
        int n = row.path(field).intValue();
        if (n < 0 || !zero && n == 0) throw new IllegalArgumentException("Invalid " + field);
        return n;
    }

    private static void exactKeys(JsonNode row, Set<String> expected) {
        if (row == null || !row.isObject()) throw new IllegalArgumentException("Object required");
        Set<String> actual = new HashSet<>();
        row.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected))
            throw new IllegalArgumentException("Unexpected or missing initial/model field");
    }

    private static final class Position {
        final String exchange;
        int quantity;
        BigDecimal cost;

        Position(String exchange, int quantity, BigDecimal cost) {
            this.exchange = exchange;
            this.quantity = quantity;
            this.cost = cost;
        }

        BigDecimal average() {
            return cost.divide(BigDecimal.valueOf(quantity), 12, RoundingMode.HALF_UP);
        }
    }

    private static final class Order {
        final String no, exchange, symbol;
        final int quantity;
        final BigDecimal limit;
        final boolean market, buy;
        final Instant at;
        int filled, cancelled;
        BigDecimal fillNotional = ZERO;

        Order(
                String no,
                String side,
                String exchange,
                String symbol,
                int quantity,
                BigDecimal limit,
                boolean market,
                Instant at) {
            this.no = no;
            this.buy = "BUY".equals(side);
            this.exchange = exchange;
            this.symbol = symbol;
            this.quantity = quantity;
            this.limit = limit;
            this.market = market;
            this.at = at;
        }

        int remaining() {
            return quantity - filled - cancelled;
        }
    }

    private static final class Quote {
        final BigDecimal bid, ask;
        final Instant at;
        final int bidSize, askSize;
        int bidRemaining, askRemaining;

        Quote(BigDecimal bid, BigDecimal ask, Instant at, int bidSize, int askSize) {
            this.bid = bid;
            this.ask = ask;
            this.at = at;
            this.bidSize = bidSize;
            this.askSize = askSize;
            this.bidRemaining = bidSize;
            this.askRemaining = askSize;
        }
    }

    private record Receivable(String fillId, BigDecimal amount, Instant at) {}
}
