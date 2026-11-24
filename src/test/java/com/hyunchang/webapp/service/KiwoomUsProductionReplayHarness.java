package com.hyunchang.webapp.service;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.entity.*;
import com.hyunchang.webapp.repository.*;
import com.hyunchang.webapp.service.kiwoom.KiwoomUsAutoTradeState;
import com.hyunchang.webapp.util.KiwoomApiRateLimiter;
import com.hyunchang.webapp.util.KiwoomUsMarketHours;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.mockito.MockedStatic;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;

/**
 * Test-only actual-service replay. External observations are supplied, never invented by the
 * engine. No Spring context, scheduler, socket, database or live authentication is created.
 */
final class KiwoomUsProductionReplayHarness implements AutoCloseable {
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static final List<String> SETTINGS =
            List.of(
                    "maxOrderPercent",
                    "maxPositions",
                    "dailyMaxBuys",
                    "minChangePercent",
                    "maxChangePercent",
                    "minVolumeRatio",
                    "fundamentalFilterEnabled",
                    "maxForwardPe",
                    "minRoePercent",
                    "maxSpreadPercent",
                    "stopLossPercent",
                    "takeProfitPercent",
                    "takeProfitPercent2",
                    "maxHoldingDays",
                    "symbolCooldownDays",
                    "dailyLossLimitPercent",
                    "signalMode",
                    "minRelativeStrengthPercent",
                    "riskPerTradePercent",
                    "atrStopMultiplier",
                    "maxEntryExtensionAtr",
                    "trailingStopAtrMultiplier",
                    "trailingActivationR",
                    "maxHoldingTradingDays");
    private final JsonNode input;
    private final KiwoomUsReplayLedger ledger;
    private KiwoomUsTradeService.Order stagedOrder;
    private Object[] stagedCancel;
    private JsonNode frame;
    private Instant now;
    private final List<KiwoomUsTradeProposal> proposals = new ArrayList<>();
    private final Map<String, KiwoomUsAccountHolding> holdings = new LinkedHashMap<>();
    private final Deque<JsonNode> responses = new ArrayDeque<>();
    private final ArrayNode writes = JSON.createArrayNode();
    private final ArrayNode attemptedOrders = JSON.createArrayNode();
    private final ArrayNode audit = JSON.createArrayNode();
    private final ArrayNode trace = JSON.createArrayNode();
    private final List<Object> repositories = new ArrayList<>();
    private final KiwoomUsAccountRisk riskEntity = new KiwoomUsAccountRisk();
    private final KiwoomUsControlState controlEntity = new KiwoomUsControlState();
    private final KiwoomUsAutoTradeState state;
    private final KiwoomUsAccountRiskService risk;
    private final KiwoomUsAutoTradeService service;
    private final MockedStatic<LocalDateTime> localClock;
    private final MockedStatic<Instant> instantClock;
    private final MockedStatic<KiwoomApiRateLimiter> limiter;

    KiwoomUsProductionReplayHarness(JsonNode supplied) {
        if (supplied.has("execution_mode")
                && !"CAUSAL_LEDGER".equals(supplied.path("execution_mode").asText()))
            throw new IllegalArgumentException("Unsupported execution mode");
        boolean causal = "CAUSAL_LEDGER".equals(supplied.path("execution_mode").asText());
        if (causal) {
            validateCausalEnvelope(supplied);
            ledger =
                    new KiwoomUsReplayLedger(
                            supplied.path("ledger_initial"), supplied.path("execution_model"));
            ledger.advanceFrame(supplied.path("frames").get(0));
            ObjectNode prepared = supplied.deepCopy();
            prepared.put("initial_risk_equity_usd", ledger.initialEquity());
            for (JsonNode value : prepared.path("frames")) {
                ObjectNode f = (ObjectNode) value;
                f.set("account", ledgerAccount());
                ObjectNode broker = f.putObject("broker");
                for (String kind : List.of("open_orders", "fills", "history"))
                    broker.putObject(kind).putArray("result_list");
                ObjectNode available = broker.putObject("orderability");
                for (var r : f.path("rankings"))
                    available.set(
                            r.path("symbol").asText(),
                            ledger.orderability(
                                    r.path("symbol").asText(),
                                    r.path("currentPrice").decimalValue()));
                broker.putArray("write_responses");
            }
            input = prepared;
        } else {
            ledger = null;
            input = supplied.deepCopy();
        }
        validate(input); // Validate the entire input before any simulated decision can run.
        now = Instant.parse(input.path("frames").get(0).path("at").asText());
        localClock = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS);
        localClock
                .when(LocalDateTime::now)
                .thenAnswer(call -> LocalDateTime.ofInstant(now, ZoneId.systemDefault()));
        localClock
                .when(() -> LocalDateTime.now(any(ZoneId.class)))
                .thenAnswer(call -> LocalDateTime.ofInstant(now, call.getArgument(0)));
        instantClock = mockStatic(Instant.class, CALLS_REAL_METHODS);
        instantClock.when(Instant::now).thenAnswer(call -> now);
        limiter = mockStatic(KiwoomApiRateLimiter.class);
        limiter.when(() -> KiwoomApiRateLimiter.reserveDelayMs(anyLong())).thenReturn(0L);

        var props = new KiwoomProperties();
        // Public fixture strings only; no environment variable or credential file is read.
        props.setAppKey("REPLAY_FIXTURE");
        props.setSecretKey("REPLAY_FIXTURE");
        props.setAccountNo("REPLAY_FIXTURE");
        props.getUs().setTradeEnabled(true);
        props.getUs().setStrategyEnabled(true);
        var controlRepo = mock(KiwoomUsControlStateRepository.class);
        repositories.add(controlRepo);
        when(controlRepo.findById(1L)).thenReturn(Optional.of(controlEntity));
        when(controlRepo.save(any())).thenAnswer(call -> call.getArgument(0));
        state = new KiwoomUsAutoTradeState(controlRepo);
        state.setAutoTrading(true);
        var riskRepo = mock(KiwoomUsAccountRiskRepository.class);
        repositories.add(riskRepo);
        when(riskRepo.findById(1L)).thenReturn(Optional.of(riskEntity));
        when(riskRepo.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        risk = new KiwoomUsAccountRiskService(riskRepo, mock(KiwoomUsCapitalFlowRepository.class));
        risk.activate(input.path("initial_risk_equity_usd").decimalValue(), true, true);

        var auth = mock(KiwoomAuthService.class);
        when(auth.getAccessToken()).thenReturn(Mono.just("REPLAY_FIXTURE_TOKEN"));
        var trade =
                spy(
                        new KiwoomUsTradeService(
                                props,
                                auth,
                                state,
                                WebClient.builder()
                                        .exchangeFunction(
                                                request -> {
                                                    String api =
                                                            request.headers().getFirst("api-id");
                                                    if (!Set.of("ust20000", "ust20001", "ust20003")
                                                            .contains(api))
                                                        throw new AssertionError(
                                                                "Unexpected external request boundary: "
                                                                        + api);
                                                    JsonNode scripted;
                                                    if (ledger != null) {
                                                        ObjectNode response;
                                                        if (api.equals("ust20003")) {
                                                            if (stagedCancel == null)
                                                                throw new AssertionError(
                                                                        "Unstaged cancellation");
                                                            response =
                                                                    ledger.cancel(
                                                                            (String)
                                                                                    stagedCancel[0],
                                                                            (String)
                                                                                    stagedCancel[1],
                                                                            (String)
                                                                                    stagedCancel[2],
                                                                            (Integer)
                                                                                    stagedCancel[
                                                                                            3]);
                                                            stagedCancel = null;
                                                        } else {
                                                            if (stagedOrder == null
                                                                    || !api.equals(
                                                                            stagedOrder
                                                                                            .side()
                                                                                            .equals(
                                                                                                    "BUY")
                                                                                    ? "ust20000"
                                                                                    : "ust20001"))
                                                                throw new AssertionError(
                                                                        "Unstaged order");
                                                            var o = stagedOrder;
                                                            response =
                                                                    ledger.submit(
                                                                            o.side(),
                                                                            o.exchange(),
                                                                            o.symbol(),
                                                                            o.quantity(),
                                                                            o.price(),
                                                                            o.market());
                                                            stagedOrder = null;
                                                        }
                                                        scripted =
                                                                JSON.createObjectNode()
                                                                        .put("api_id", api)
                                                                        .set("response", response);
                                                    } else {
                                                        scripted = responses.pollFirst();
                                                        if (scripted == null
                                                                || !api.equals(
                                                                        scripted.path("api_id")
                                                                                .asText()))
                                                            throw new AssertionError(
                                                                    "Missing/mismatched scripted write response: "
                                                                            + api);
                                                    }
                                                    writes.addObject()
                                                            .put("frame", frame.path("id").asText())
                                                            .put("at", now.toString())
                                                            .put("api_id", api)
                                                            .set(
                                                                    "response",
                                                                    scripted.path("response")
                                                                            .deepCopy());
                                                    return Mono.just(
                                                            ClientResponse.create(HttpStatus.OK)
                                                                    .header(
                                                                            "Content-Type",
                                                                            "application/json")
                                                                    .body(
                                                                            scripted.path(
                                                                                            "response")
                                                                                    .toString())
                                                                    .build());
                                                })));
        doAnswer(
                        c ->
                                Mono.just(
                                        ledger == null
                                                ? frame.path("account").path("deposit")
                                                : ledger.deposit()))
                .when(trade)
                .getDepositDetail();
        doAnswer(
                        c ->
                                Mono.just(
                                        ledger == null
                                                ? frame.path("account").path("balance")
                                                : ledger.balance()))
                .when(trade)
                .getBalance();
        doAnswer(
                        c ->
                                Mono.just(
                                        ledger == null
                                                ? frame.path("broker").path("open_orders")
                                                : ledger.openOrders()))
                .when(trade)
                .getOpenOrders();
        doAnswer(
                        c ->
                                Mono.just(
                                        ledger == null
                                                ? frame.path("broker").path("fills")
                                                : ledger.fills()))
                .when(trade)
                .getTodayFills();
        doAnswer(
                        c ->
                                Mono.just(
                                        ledger == null
                                                ? frame.path("broker").path("history")
                                                : ledger.history()))
                .when(trade)
                .getOrderHistory(any(), any());
        doAnswer(
                        c ->
                                Mono.just(
                                        new KiwoomUsTradeService.KrwOrderServiceStatus(
                                                "REPLAY", "snapshot", "")))
                .when(trade)
                .getKrwOrderServiceStatus();
        doAnswer(
                        c ->
                                Mono.just(
                                        ledger == null
                                                ? boundaryRequired(
                                                        frame.path("broker").path("orderability"),
                                                        c.getArgument(1))
                                                : ledger.orderability(
                                                        c.getArgument(1), c.getArgument(2))))
                .when(trade)
                .getOrderableQuantity(anyString(), anyString(), any());
        doAnswer(
                        c -> {
                            var q = boundaryRequired(frame.path("quotes"), c.getArgument(1));
                            return Mono.just(
                                    new KiwoomUsTradeService.OrderBookQuote(
                                            q.path("bid").decimalValue(),
                                            q.path("ask").decimalValue()));
                        })
                .when(trade)
                .getOrderBook(anyString(), anyString());
        doAnswer(
                        c -> {
                            var bars = new ArrayList<KiwoomUsTradeService.DailyBar>();
                            for (var b :
                                    boundaryRequired(
                                            frame.path("completed_bars"), c.getArgument(1)))
                                bars.add(
                                        new KiwoomUsTradeService.DailyBar(
                                                LocalDate.parse(b.path("date").asText()),
                                                b.path("open").asDouble(),
                                                b.path("high").asDouble(),
                                                b.path("low").asDouble(),
                                                b.path("close").asDouble(),
                                                b.path("volume").asLong()));
                            return Mono.just(bars);
                        })
                .when(trade)
                .getDailyBars(anyString(), anyString());
        doAnswer(
                        c -> {
                            var ranked = new ArrayList<KiwoomUsTradeService.RankedStock>();
                            for (var r : frame.path("rankings"))
                                ranked.add(
                                        new KiwoomUsTradeService.RankedStock(
                                                r.path("rank").asInt(),
                                                r.path("exchange").asText(),
                                                r.path("symbol").asText(),
                                                r.path("name").asText(),
                                                r.path("currentPrice").decimalValue(),
                                                r.path("changePercent").asDouble(),
                                                r.path("accumulatedVolume").asLong(),
                                                r.path("previousVolume").asLong(),
                                                r.path("tradedValue").decimalValue()));
                            return Mono.just(ranked);
                        })
                .when(trade)
                .getTradeValueTop();
        doAnswer(
                        c -> {
                            ObjectNode attempt =
                                    JSON.valueToTree(
                                            c.getArgument(0, KiwoomUsTradeService.Order.class));
                            attempt.put("frame", frame.path("id").asText())
                                    .put("at", now.toString());
                            attemptedOrders.add(attempt);
                            stagedOrder = c.getArgument(0);
                            return c.callRealMethod(); // Includes the real last-moment funding
                            // checks.
                        })
                .when(trade)
                .placeOrder(any());
        doAnswer(
                        c -> {
                            stagedCancel = c.getArguments().clone();
                            return c.callRealMethod();
                        })
                .when(trade)
                .cancelOrder(anyString(), anyString(), anyString(), anyInt());

        var fundamentals = mock(KiwoomUsFundamentalService.class);
        when(fundamentals.lookup(anyString()))
                .thenAnswer(
                        c -> {
                            var f = boundaryRequired(frame.path("fundamentals"), c.getArgument(0));
                            var status =
                                    KiwoomUsFundamentalService.LookupStatus.valueOf(
                                            f.path("lookup_status").asText());
                            boolean available =
                                    Set.of(
                                                    KiwoomUsFundamentalService.LookupStatus
                                                            .CACHE_FRESH,
                                                    KiwoomUsFundamentalService.LookupStatus
                                                            .YAHOO_REFRESHED,
                                                    KiwoomUsFundamentalService.LookupStatus
                                                            .CACHE_STALE_FALLBACK)
                                            .contains(status);
                            return new KiwoomUsFundamentalService.LookupResult(
                                    available
                                            ? new KiwoomUsFundamentalService.FundamentalSnapshot(
                                                    f.path("forwardPe").asDouble(),
                                                    f.path("trailingPe").asDouble(),
                                                    f.path("roePercent").asDouble(),
                                                    LocalDateTime.ofInstant(
                                                            Instant.parse(
                                                                    f.path("available_at")
                                                                            .asText()),
                                                            ZoneId.systemDefault()))
                                            : null,
                                    status,
                                    "REPLAY_SNAPSHOT",
                                    "explicit point-in-time input");
                        });
        var universe = mock(KiwoomUsIndexUniverseService.class);
        when(universe.isEligible(anyString()))
                .thenAnswer(
                        c ->
                                boundaryRequired(frame.path("universe"), c.getArgument(0))
                                        .path("eligible")
                                        .asBoolean());
        when(universe.membershipLabel(anyString()))
                .thenAnswer(
                        c ->
                                boundaryRequired(frame.path("universe"), c.getArgument(0))
                                        .path("label")
                                        .asText());
        var settingsRepo = mock(KiwoomUsStrategySettingsRepository.class);
        repositories.add(settingsRepo);
        KiwoomUsStrategySettings settings =
                JSON.convertValue(input.path("settings"), KiwoomUsStrategySettings.class);
        when(settingsRepo.findById(1L)).thenReturn(Optional.of(settings));
        var proposalRepo = proposalRepository();
        var holdingRepo = mock(KiwoomUsAccountHoldingRepository.class);
        repositories.add(holdingRepo);
        when(holdingRepo.findByActiveTrueOrderByIdAsc())
                .thenAnswer(
                        c ->
                                holdings.values().stream()
                                        .filter(KiwoomUsAccountHolding::isActive)
                                        .toList());
        when(holdingRepo.findByExchangeAndSymbol(anyString(), anyString()))
                .thenAnswer(
                        c ->
                                Optional.ofNullable(
                                        holdings.get(c.getArgument(0) + ":" + c.getArgument(1))));
        when(holdingRepo.save(any()))
                .thenAnswer(
                        c -> {
                            KiwoomUsAccountHolding h = c.getArgument(0);
                            holdings.put(h.getExchange() + ":" + h.getSymbol(), h);
                            return h;
                        });
        var auditService = mock(KiwoomUsAuditService.class);
        doAnswer(
                        c -> {
                            audit.addObject()
                                    .put("at", now.toString())
                                    .put("type", c.getArgument(0, String.class))
                                    .put("message", c.getArgument(2, String.class));
                            return null;
                        })
                .when(auditService)
                .log(anyString(), nullable(Long.class), anyString());
        var runs = mock(KiwoomUsStrategyRunRepository.class);
        repositories.add(runs);
        when(runs.save(any())).thenAnswer(c -> c.getArgument(0));
        service =
                new KiwoomUsAutoTradeService(
                        props,
                        trade,
                        new KiwoomUsStrategySettingsService(settingsRepo, props),
                        fundamentals,
                        universe,
                        new KiwoomUsTechnicalSignalService(trade),
                        state,
                        holdingRepo,
                        proposalRepo,
                        runs,
                        auditService,
                        mock(KiwoomUsEventService.class),
                        risk);
    }

    private KiwoomUsTradeProposalRepository proposalRepository() {
        var repo = mock(KiwoomUsTradeProposalRepository.class);
        repositories.add(repo);
        when(repo.findByStatusIn(any()))
                .thenAnswer(
                        c -> {
                            Collection<?> statuses = c.getArgument(0);
                            return proposals.stream()
                                    .filter(p -> statuses.contains(p.getStatus()))
                                    .toList();
                        });
        when(repo.save(any())).thenAnswer(c -> save(c.getArgument(0)));
        when(repo.saveAndFlush(any())).thenAnswer(c -> save(c.getArgument(0)));
        when(repo.findByBrokerOrderNo(anyString()))
                .thenAnswer(
                        c ->
                                proposals.stream()
                                        .filter(
                                                p ->
                                                        Objects.equals(
                                                                p.getBrokerOrderNo(),
                                                                c.getArgument(0)))
                                        .findFirst());
        when(repo.findByExchangeAndSymbolOrderByIdAsc(anyString(), anyString()))
                .thenAnswer(
                        c ->
                                proposals.stream()
                                        .filter(
                                                p ->
                                                        p.getExchange().equals(c.getArgument(0))
                                                                && p.getSymbol()
                                                                        .equals(c.getArgument(1)))
                                        .toList());
        when(repo.countByActionAndStatusAndOrderedAtAfter(any(), any(), any()))
                .thenAnswer(
                        c ->
                                proposals.stream()
                                        .filter(
                                                p ->
                                                        p.getAction() == c.getArgument(0)
                                                                && p.getStatus() == c.getArgument(1)
                                                                && p.getOrderedAt()
                                                                        .isAfter(c.getArgument(2)))
                                        .count());
        when(repo.existsBySymbolAndActionAndFilledQuantityGreaterThanAndOrderedAtAfter(
                        anyString(), any(), anyInt(), any()))
                .thenAnswer(
                        c ->
                                proposals.stream()
                                        .anyMatch(
                                                p ->
                                                        p.getSymbol().equals(c.getArgument(0))
                                                                && p.getAction() == c.getArgument(1)
                                                                && p.getFilledQuantity()
                                                                        > c.getArgument(
                                                                                2, Integer.class)
                                                                && p.getOrderedAt()
                                                                        .isAfter(
                                                                                c.getArgument(3))));
        when(repo.existsBySymbolAndActionAndStatusInAndOrderedAtAfter(
                        anyString(), any(), any(), any()))
                .thenAnswer(
                        c -> {
                            Collection<?> statuses = c.getArgument(2);
                            return proposals.stream()
                                    .anyMatch(
                                            p ->
                                                    p.getSymbol().equals(c.getArgument(0))
                                                            && p.getAction() == c.getArgument(1)
                                                            && statuses.contains(p.getStatus())
                                                            && p.getOrderedAt()
                                                                    .isAfter(c.getArgument(3)));
                        });
        return repo;
    }

    private KiwoomUsTradeProposal save(KiwoomUsTradeProposal p) {
        if (p.getId() == null) {
            ReflectionTestUtils.setField(p, "id", (long) proposals.size() + 1);
            proposals.add(p);
        }
        return p;
    }

    ObjectNode run() {
        boolean first = true;
        for (JsonNode next : input.path("frames")) {
            frame = next;
            now = Instant.parse(frame.path("at").asText());
            if (ledger != null) {
                if (!first) ledger.advanceFrame(frame);
                ((ObjectNode) frame).set("account", ledgerAccount());
            }
            first = false;
            responses.clear();
            frame.path("broker").path("write_responses").forEach(responses::add);
            for (JsonNode action : frame.path("actions")) {
                ObjectNode result =
                        trace.addObject()
                                .put("frame", frame.path("id").asText())
                                .put("at", now.toString())
                                .put("action", action.asText());
                ObjectNode before = result.putObject("before");
                before.set("risk", JSON.valueToTree(risk.status()));
                before.set("proposals", JSON.valueToTree(proposals));
                before.set("holdings", JSON.valueToTree(holdings.values()));
                before.set(
                        "broker_account",
                        ledger == null ? frame.path("account").deepCopy() : ledgerAccount());
                if (ledger != null) before.set("ledger", ledgerSnapshot());
                switch (action.asText()) {
                    case "DECIDE" -> {
                        int auditStart = audit.size();
                        result.set("selection_input", frame.path("rankings").deepCopy());
                        var decision = service.decide("OFFLINE_REPLAY", true);
                        result.set("decision", JSON.valueToTree(decision));
                        // A failed early readiness check can leave the service's previous list.
                        // Keep its scope explicit rather than attributing stale candidates to this
                        // cycle.
                        result.set("candidates", JSON.valueToTree(service.candidates()));
                        result.put("candidate_snapshot_scope", "LATEST_SERVICE_STATE");
                        result.put(
                                "candidate_list_refreshed",
                                Set.of("ORDERED", "NO_CANDIDATE", "PREVIEW", "PAUSED")
                                        .contains(decision.status()));
                        ArrayNode cycleAudit = result.putArray("selection_audit");
                        for (int i = auditStart; i < audit.size(); i++)
                            cycleAudit.add(audit.get(i).deepCopy());
                    }
                    case "MONITOR" ->
                            service.scheduledAccountAndExitSync(); // Direct call; no scheduler is
                        // registered.
                    case "RECONCILE" -> service.reconcileOrders();
                    default -> throw new AssertionError("unvalidated action");
                }
                for (Object repo : repositories)
                    for (var invocation : mockingDetails(repo).getInvocations())
                        if (invocation.stubInfo() == null
                                && !invocation.getMethod().getName().equals("flush"))
                            throw new AssertionError(
                                    "Unstubbed repository call: " + invocation.getMethod());
                result.set("risk", JSON.valueToTree(risk.status()));
                result.set("proposals", JSON.valueToTree(proposals));
                result.set("holdings", JSON.valueToTree(holdings.values()));
                result.put("autoTrading", state.isAutoTrading());
                if (ledger != null) result.set("ledger", ledgerSnapshot());
            }
            if (!responses.isEmpty())
                throw new AssertionError(
                        "Unused expected write response in " + frame.path("id").asText());
        }
        ObjectNode report =
                JSON.createObjectNode()
                        .put("schema_version", 1)
                        .put("dataset_kind", input.path("dataset_kind").asText())
                        .put(
                                "execution_mode",
                                ledger == null
                                        ? "OFFLINE_ACTUAL_SERVICES"
                                        : "OFFLINE_CAUSAL_ACTUAL_SERVICES")
                        .put("real_order_count", 0)
                        .put("performance_verified", false);
        report.putNull("return_pct");
        report.putNull("benchmark_return_pct");
        report.put(
                "limitation",
                "Scripted observations and fills; execution-path trace, not a market performance backtest or database durability test.");
        report.put(
                "initialization",
                "NEW_POLICY_NO_PRIOR_AUTOMATION: explicit initial baseline, no historic automated orders/cooldowns/latched risk restored; manual holdings remain in supplied broker balance.");
        report.set("settings", input.path("settings").deepCopy());
        report.put(
                "clock",
                "Snapshot Instant and LocalDateTime only; production market-calendar/session rules are not overridden.");
        report.set("trace", trace);
        report.set("attempted_orders", attemptedOrders);
        report.set("broker_boundary_writes", writes);
        report.set("audit", audit);
        if (ledger != null) {
            report.set("ledger", ledger.report());
            report.set("ledger_initial", input.path("ledger_initial").deepCopy());
            report.set("execution_model", input.path("execution_model").deepCopy());
            report.put(
                    "schedule",
                    "Actions are explicit per-frame DECIDE/MONITOR/RECONCILE calls. No scheduler or missing 10-minute decision / 30-second monitor events are synthesized; historical performance requires complete observation and action schedules.");
            report.put(
                    "limitation",
                    "Causal simulated fills from later quote frames; synthetic regression is not historical profitability. No actual broker, database durability, FX, corporate actions or external cashflows.");
            report.put(
                    "initialization",
                    "NEW_POLICY_NO_PRIOR_AUTOMATION: baseline derived from initial ledger cash and bid-valued holdings; prior risk and automation state cannot be restored.");
        }
        return report;
    }

    private ObjectNode ledgerSnapshot() {
        return ledger.snapshot();
    }

    private ObjectNode ledgerAccount() {
        ObjectNode value = JSON.createObjectNode();
        value.set("deposit", ledger.deposit());
        value.set("balance", ledger.balance());
        return value;
    }

    private static void validateCausalEnvelope(JsonNode supplied) {
        Set<String> top =
                Set.of(
                        "schema_version",
                        "dataset_kind",
                        "initialization",
                        "execution_mode",
                        "settings",
                        "ledger_initial",
                        "execution_model",
                        "frames");
        supplied.fieldNames()
                .forEachRemaining(
                        key -> {
                            if (!top.contains(key))
                                throw new IllegalArgumentException(
                                        "Unsupported causal input: " + key);
                        });
        JsonNode frames = required(supplied, "frames");
        if (!frames.isArray() || frames.isEmpty())
            throw new IllegalArgumentException("Frames required");
        Set<String> fields =
                Set.of(
                        "id",
                        "at",
                        "observed_at",
                        "actions",
                        "rankings",
                        "quotes",
                        "fundamentals",
                        "universe",
                        "completed_bars");
        for (JsonNode f : frames) {
            if (!f.isObject()) throw new IllegalArgumentException("Frame object required");
            f.fieldNames()
                    .forEachRemaining(
                            key -> {
                                if (!fields.contains(key))
                                    throw new IllegalArgumentException(
                                            "Causal frames cannot supply account/broker state: "
                                                    + key);
                            });
            for (JsonNode q : required(f, "quotes"))
                for (String size : List.of("bid_size", "ask_size"))
                    if (!required(q, size).isIntegralNumber()
                            || q.path(size).asLong() < 0
                            || !q.path(size).canConvertToInt())
                        throw new IllegalArgumentException(
                                "Nonnegative integer quote liquidity required");
        }
    }

    static JsonNode required(JsonNode parent, String name) {
        var value = parent.get(name);
        if (value == null || value.isNull())
            throw new IllegalArgumentException("Missing input: " + name);
        return value;
    }

    private static JsonNode boundaryRequired(JsonNode parent, String name) {
        try {
            return required(parent, name);
        } catch (IllegalArgumentException missing) {
            throw new AssertionError("Unscripted external observation: " + name, missing);
        }
    }

    static void validate(JsonNode input) {
        if (required(input, "schema_version").asInt() != 1
                || !Set.of("synthetic", "real_point_in_time")
                        .contains(required(input, "dataset_kind").asText()))
            throw new IllegalArgumentException("Unsupported schema/dataset kind");
        if (!"NEW_POLICY_NO_PRIOR_AUTOMATION".equals(required(input, "initialization").asText()))
            throw new IllegalArgumentException(
                    "Only explicit new-policy replay supported; existing risk/order state cannot be reset");
        for (String key :
                List.of("initial_state", "initial_proposals", "initial_risk", "initial_control"))
            if (input.has(key))
                throw new IllegalArgumentException(
                        "Existing state restoration not supported: " + key);
        JsonNode settings = required(input, "settings");
        for (String key : SETTINGS) {
            JsonNode value = required(settings, key);
            if (key.equals("signalMode"))
                KiwoomUsStrategySettings.SignalMode.valueOf(value.asText());
            else if (key.equals("fundamentalFilterEnabled")) {
                if (!value.isBoolean())
                    throw new IllegalArgumentException("Boolean setting required");
            } else if (!value.isNumber()
                    || !Double.isFinite(value.asDouble())
                    || value.asDouble() < 0)
                throw new IllegalArgumentException("Numeric setting required: " + key);
        }
        settings.fieldNames()
                .forEachRemaining(
                        key -> {
                            if (!SETTINGS.contains(key))
                                throw new IllegalArgumentException("Unknown settings field " + key);
                        });
        JSON.convertValue(
                settings,
                KiwoomUsStrategySettings.class); // Fail before static clocks are installed.
        if (!required(input, "initial_risk_equity_usd").isNumber()
                || input.path("initial_risk_equity_usd").decimalValue().signum() <= 0)
            throw new IllegalArgumentException("Positive baseline required");
        JsonNode frames = required(input, "frames");
        if (!frames.isArray() || frames.isEmpty())
            throw new IllegalArgumentException("Frames required");
        Instant prior = null;
        Set<String> ids = new HashSet<>();
        for (JsonNode f : frames) {
            if (!ids.add(required(f, "id").asText()) || f.path("id").asText().isBlank())
                throw new IllegalArgumentException("Duplicate/empty frame id");
            Instant at = Instant.parse(required(f, "at").asText());
            if (prior != null && !at.isAfter(prior))
                throw new IllegalArgumentException("Frames not strictly ordered");
            prior = at;
            timestamp(required(f, "observed_at"), at, true);
            JsonNode actions = required(f, "actions");
            if (!actions.isArray() || actions.isEmpty())
                throw new IllegalArgumentException("Actions required");
            for (var a : actions)
                if (!Set.of("DECIDE", "MONITOR", "RECONCILE").contains(a.asText()))
                    throw new IllegalArgumentException("Unknown action");
            var account = required(f, "account");
            var deposit = required(account, "deposit");
            var balance = required(account, "balance");
            for (String k :
                    List.of("d0_usd_fx_entr", "krw_ord_set_amt", "won_dfr_amt", "won_etc_loana"))
                required(deposit, k);
            for (int day = 1; day <= 4; day++)
                for (String suffix : List.of("fx_entr", "sell_excta", "buy_excta"))
                    required(deposit, "d" + day + "_usd_" + suffix);
            required(balance, "crnc_code");
            if (!required(balance, "result_list").isArray())
                throw new IllegalArgumentException("Balance rows required");
            for (var row : balance.path("result_list")) {
                for (String key :
                        List.of(
                                "stk_cd",
                                "stex_cd",
                                "crnc_code",
                                "poss_qty",
                                "sell_alowq",
                                "avg_pric",
                                "now_pric",
                                "pl_rt")) required(row, key);
                if (row.path("poss_qty").asInt() > 0)
                    required(f.path("quotes"), row.path("stk_cd").asText());
            }
            var broker = required(f, "broker");
            for (String k :
                    List.of("open_orders", "fills", "history", "orderability", "write_responses"))
                required(broker, k);
            for (String k : List.of("open_orders", "fills", "history"))
                if (!required(broker.path(k), "result_list").isArray())
                    throw new IllegalArgumentException("Broker rows required");
            for (var orderability : broker.path("orderability"))
                for (String key :
                        List.of(
                                "krw_ord_rqst_yn",
                                "krw_ord_set_amt",
                                "krw_ord_alowa_100",
                                "krw_ord_alowq_100",
                                "min_ord_alowq",
                                "fc_entra",
                                "fc_uncl_amt",
                                "ord_alowa",
                                "min_ord_alowa")) required(orderability, key);
            if (f == frames.get(0))
                for (String k : List.of("open_orders", "fills", "history"))
                    if (!broker.path(k).path("result_list").isEmpty())
                        throw new IllegalArgumentException(
                                "First frame must start without prior automated orders/fills");
            if (!broker.path("write_responses").isArray())
                throw new IllegalArgumentException("Write responses required");
            for (var w : broker.path("write_responses")) {
                if (!Set.of("ust20000", "ust20001", "ust20003")
                        .contains(required(w, "api_id").asText()))
                    throw new IllegalArgumentException("Unsupported write");
                required(required(w, "response"), "return_code");
            }
            for (String k : List.of("quotes", "fundamentals", "universe", "completed_bars"))
                if (!required(f, k).isObject())
                    throw new IllegalArgumentException("Object required: " + k);
            if (!required(f, "rankings").isArray())
                throw new IllegalArgumentException("Rankings array required");
            for (var r : f.path("rankings")) {
                for (String k :
                        List.of(
                                "rank",
                                "exchange",
                                "symbol",
                                "name",
                                "currentPrice",
                                "changePercent",
                                "accumulatedVolume",
                                "previousVolume",
                                "tradedValue")) required(r, k);
                String symbol = r.path("symbol").asText();
                for (String k : List.of("quotes", "fundamentals", "universe", "completed_bars"))
                    required(f.path(k), symbol);
                required(broker.path("orderability"), symbol);
            }
            for (var q : f.path("quotes")) {
                timestamp(required(q, "available_at"), at, true);
                if (!required(q, "bid").isNumber()
                        || !required(q, "ask").isNumber()
                        || q.path("bid").decimalValue().signum() <= 0
                        || q.path("ask").decimalValue().compareTo(q.path("bid").decimalValue()) < 0)
                    throw new IllegalArgumentException("Invalid quote");
            }
            for (var item : f.path("fundamentals")) {
                timestamp(required(item, "available_at"), at, false);
                var status =
                        KiwoomUsFundamentalService.LookupStatus.valueOf(
                                required(item, "lookup_status").asText());
                if (Set.of(
                                KiwoomUsFundamentalService.LookupStatus.CACHE_FRESH,
                                KiwoomUsFundamentalService.LookupStatus.YAHOO_REFRESHED,
                                KiwoomUsFundamentalService.LookupStatus.CACHE_STALE_FALLBACK)
                        .contains(status))
                    for (String k : List.of("forwardPe", "trailingPe", "roePercent"))
                        if (!required(item, k).isNumber())
                            throw new IllegalArgumentException("Invalid fundamental");
            }
            for (var item : f.path("universe")) {
                timestamp(required(item, "available_at"), at, false);
                if (!required(item, "eligible").isBoolean())
                    throw new IllegalArgumentException("Eligibility required");
                required(item, "label");
            }
            LocalDate session = at.atZone(KiwoomUsMarketHours.ET).toLocalDate();
            if (!f.path("rankings").isEmpty()) {
                required(f.path("completed_bars"), "SPY");
                required(f.path("completed_bars"), "QQQ");
            }
            for (var bars : f.path("completed_bars")) {
                if (!bars.isArray() || bars.size() < 51)
                    throw new IllegalArgumentException("At least 51 completed bars required");
                LocalDate last = null;
                for (var b : bars) {
                    LocalDate date = LocalDate.parse(required(b, "date").asText());
                    if (!date.isBefore(session) || last != null && !date.isAfter(last))
                        throw new IllegalArgumentException(
                                "Future/duplicate/unordered completed bar");
                    last = date;
                    for (String k : List.of("open", "high", "low", "close", "volume"))
                        if (!required(b, k).isNumber() || b.path(k).asDouble() <= 0)
                            throw new IllegalArgumentException("Invalid bar field " + k);
                }
            }
        }
    }

    private static void timestamp(JsonNode value, Instant at, boolean fresh) {
        Instant t = Instant.parse(value.asText());
        if (t.isAfter(at) || fresh && t.isBefore(at.minusSeconds(90)))
            throw new IllegalArgumentException("Future/stale observation");
    }

    static ObjectNode productionHashes() throws Exception {
        ObjectNode hashes = JSON.createObjectNode();
        for (String relative :
                List.of(
                        "service/KiwoomUsAutoTradeService.java",
                        "service/KiwoomUsTradeService.java",
                        "service/KiwoomUsTechnicalSignalService.java",
                        "service/KiwoomUsAccountRiskService.java",
                        "service/KiwoomUsStrategySettingsService.java",
                        "service/kiwoom/KiwoomUsAutoTradeState.java",
                        "util/KiwoomUsMarketHours.java",
                        "entity/KiwoomUsAccountHolding.java",
                        "entity/KiwoomUsTradeProposal.java",
                        "entity/KiwoomUsStrategySettings.java",
                        "entity/KiwoomUsTrendExitPlan.java",
                        "entity/KiwoomUsAccountRisk.java",
                        "entity/KiwoomUsControlState.java")) {
            Path path = Path.of("src/main/java/com/hyunchang/webapp", relative);
            hashes.put(
                    relative,
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(Files.readAllBytes(path))));
        }
        return hashes;
    }

    @Override
    public void close() {
        limiter.close();
        instantClock.close();
        localClock.close();
    }
}
