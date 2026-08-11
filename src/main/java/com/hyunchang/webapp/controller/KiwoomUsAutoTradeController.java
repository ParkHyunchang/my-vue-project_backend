package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.entity.KiwoomUsStrategySettings;
import com.hyunchang.webapp.service.KiwoomAuthService;
import com.hyunchang.webapp.service.KiwoomUsAuditService;
import com.hyunchang.webapp.service.KiwoomUsAutoTradeService;
import com.hyunchang.webapp.service.KiwoomUsEventService;
import com.hyunchang.webapp.service.KiwoomUsStrategySettingsService;
import com.hyunchang.webapp.service.KiwoomUsTradeService;
import com.hyunchang.webapp.service.kiwoom.KiwoomUsAutoTradeState;
import com.hyunchang.webapp.util.KiwoomUsMarketHours;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/kiwoom/us/auto-trade")
@PreAuthorize("hasRole('ADMIN')")
public class KiwoomUsAutoTradeController {
    private final KiwoomProperties properties;
    private final KiwoomAuthService auth;
    private final KiwoomUsAutoTradeState state;
    private final KiwoomUsAutoTradeService service;
    private final KiwoomUsStrategySettingsService settings;
    private final KiwoomUsAuditService audit;
    private final KiwoomUsEventService events;

    public KiwoomUsAutoTradeController(
            KiwoomProperties properties,
            KiwoomAuthService auth,
            KiwoomUsAutoTradeState state,
            KiwoomUsAutoTradeService service,
            KiwoomUsStrategySettingsService settings,
            KiwoomUsAuditService audit,
            KiwoomUsEventService events) {
        this.properties = properties;
        this.auth = auth;
        this.state = state;
        this.service = service;
        this.settings = settings;
        this.audit = audit;
        this.events = events;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configured", properties.isConfigured());
        result.put("tokenValid", auth.hasUsableToken());
        result.put("autoTrading", state.isAutoTrading());
        result.put("orderEnabled", properties.getUs().isTradeEnabled());
        result.put("strategyEnabled", properties.getUs().isStrategyEnabled());
        result.put("marketOpen", KiwoomUsMarketHours.isOpen());
        result.put("entryWindow", KiwoomUsMarketHours.isEntryWindow());
        result.put("emergencyStopped", state.isEmergencyStopped());
        result.put("consecutiveApiFailures", state.getConsecutiveApiFailures());
        result.put(
                "lastApiFailureAt",
                state.getLastApiFailureAt() == null ? "" : state.getLastApiFailureAt().toString());
        result.put(
                "lastApiFailureMessage",
                state.getLastApiFailureMessage() == null ? "" : state.getLastApiFailureMessage());
        result.put(
                "cashPolicy",
                KiwoomUsTradeService.USD_CASH_SOURCE + "만 사용; 원화 자동환전/원화 주문 가능액 사용 안 함");
        return result;
    }

    @GetMapping("/summary")
    public KiwoomUsAutoTradeService.AccountSnapshot summary() {
        return service.refreshAccountSnapshot();
    }

    @GetMapping("/holdings")
    public Object holdings() {
        return service.holdings();
    }

    @GetMapping("/candidates")
    public Object candidates() {
        return service.candidates();
    }

    @GetMapping("/proposals")
    public Object proposals() {
        return service.proposals();
    }

    @GetMapping("/audit")
    public Object audit() {
        return audit.recent();
    }

    @GetMapping("/settings")
    public KiwoomUsStrategySettings settings() {
        return settings.current();
    }

    @PatchMapping("/settings")
    public KiwoomUsStrategySettings settings(@RequestBody KiwoomUsStrategySettings request) {
        return settings.save(request);
    }

    @PostMapping("/control")
    public ResponseEntity<Map<String, Object>> control(@RequestBody ControlRequest request) {
        if (request.enabled()
                && (!properties.isConfigured() || !properties.getUs().isTradeEnabled())) {
            return ResponseEntity.status(409)
                    .body(Map.of("message", "키움 운영계좌 설정과 KIWOOM_US_TRADE_ENABLED=true가 필요합니다."));
        }
        state.setAutoTrading(request.enabled());
        audit.log(
                request.enabled() ? "START" : "STOP",
                null,
                request.enabled()
                        ? "미국주식 자동매매를 시작했습니다. 매수 자금은 D+0 USD 외화예수금으로 제한됩니다."
                        : "미국주식 신규 자동주문을 중지했습니다.");
        events.publish(
                request.enabled() ? "START" : "STOP",
                request.enabled() ? "미국주식 자동매매 시작 (USD 예수금만 사용)" : "미국주식 자동매매 중지",
                null);
        return ResponseEntity.ok(Map.of("autoTrading", state.isAutoTrading()));
    }

    @PostMapping("/decide")
    public KiwoomUsAutoTradeService.DecisionResult decide() {
        return service.decide("ADMIN");
    }

    @PostMapping("/sync")
    public Map<String, Object> sync() {
        service.reconcileOrders();
        KiwoomUsAutoTradeService.AccountSnapshot snapshot = service.refreshAccountSnapshot();
        return Map.of("success", true, "snapshot", snapshot);
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> events() {
        Flux<ServerSentEvent<Map<String, Object>>> live =
                events.events()
                        .map(value -> ServerSentEvent.builder(value).event("kiwoom-us").build());
        Flux<ServerSentEvent<Map<String, Object>>> heartbeat =
                Flux.interval(Duration.ofSeconds(20))
                        .map(
                                ignored ->
                                        ServerSentEvent.<Map<String, Object>>builder()
                                                .comment("heartbeat")
                                                .build());
        return Flux.merge(live, heartbeat);
    }

    public record ControlRequest(boolean enabled) {}
}
