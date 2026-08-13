package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.entity.KiwoomUsStrategySettings;
import com.hyunchang.webapp.service.KiwoomAuthService;
import com.hyunchang.webapp.service.KiwoomUsAuditService;
import com.hyunchang.webapp.service.KiwoomUsAutoTradeService;
import com.hyunchang.webapp.service.KiwoomUsEventService;
import com.hyunchang.webapp.service.KiwoomUsIndexUniverseService;
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
import org.springframework.web.bind.annotation.RequestParam;
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
    private final KiwoomUsIndexUniverseService indexUniverse;

    public KiwoomUsAutoTradeController(
            KiwoomProperties properties,
            KiwoomAuthService auth,
            KiwoomUsAutoTradeState state,
            KiwoomUsAutoTradeService service,
            KiwoomUsStrategySettingsService settings,
            KiwoomUsAuditService audit,
            KiwoomUsEventService events,
            KiwoomUsIndexUniverseService indexUniverse) {
        this.properties = properties;
        this.auth = auth;
        this.state = state;
        this.service = service;
        this.settings = settings;
        this.audit = audit;
        this.events = events;
        this.indexUniverse = indexUniverse;
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
        result.put("marketSeason", KiwoomUsMarketHours.seasonLabel());
        result.put("regularSessionKst", KiwoomUsMarketHours.regularSessionKst());
        result.put("entrySessionKst", KiwoomUsMarketHours.entrySessionKst());
        result.put("easternNow", KiwoomUsMarketHours.now().toString());
        result.put(
                "marketHoursPolicy", "신규매수는 10:00~15:00 ET, 매도감시·체결동기화는 정규장 09:30~16:00 ET에만 실행");
        result.put("calendarPolicy", "NYSE 2026~2028 휴장일·13:00 ET 조기폐장 반영; 미등록 연도는 안전하게 주문 차단");
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
                KiwoomUsTradeService.USD_CASH_SOURCE
                        + "만 사용; 원화주문설정금이 1원이라도 있으면 시작·매수 차단; 실제 주문 직전 원화주문 서비스 해지 상태와 외화 주문가능수량 재확인; USD 1% 수수료 여유 유지");
        result.put("candidateUniversePolicy", "당일 거래대금 상위 50위 중 S&P 500 또는 NASDAQ-100 편입 종목만 허용");
        result.put("indexUniverse", indexUniverse.status());
        return result;
    }

    @GetMapping("/summary")
    public KiwoomUsAutoTradeService.AccountSnapshot summary() {
        return service.accountSummary();
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

    @GetMapping("/runs")
    public Object runs() {
        return service.runs();
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
        if (request.enabled()) {
            KiwoomUsTradeService.UsdCash cash = service.refreshUsdCash();
            if (!cash.usdOnlyBuyAllowed()) {
                audit.log("USD_CASH_BLOCK", null, cash.blockReason());
                events.publish("USD_CASH_BLOCK", cash.blockReason(), null);
                return ResponseEntity.status(409)
                        .body(
                                Map.of(
                                        "message",
                                        cash.blockReason(),
                                        "krwOrderSettingAmount",
                                        cash.krwOrderSettingAmount()));
            }
        }
        state.setAutoTrading(request.enabled());
        audit.log(
                request.enabled() ? "START" : "STOP",
                null,
                request.enabled()
                        ? "미국주식 자동매매를 시작했습니다. 원화주문설정금 0원을 확인했고, 매수 자금은 D+0 USD 외화예수금의 99% 이내로 제한됩니다."
                        : "미국주식 신규 자동주문을 중지했습니다.");
        events.publish(
                request.enabled() ? "START" : "STOP",
                request.enabled() ? "미국주식 자동매매 시작 (원화주문 차단·USD 예수금만 사용)" : "미국주식 자동매매 중지",
                null);
        return ResponseEntity.ok(Map.of("autoTrading", state.isAutoTrading()));
    }

    @PostMapping("/decide")
    public KiwoomUsAutoTradeService.DecisionResult decide(
            @RequestParam(defaultValue = "false") boolean allowOrder) {
        return service.decide("ADMIN", allowOrder);
    }

    @PostMapping("/sync")
    public Map<String, Object> sync() {
        service.reconcileOrders();
        KiwoomUsAutoTradeService.AccountSnapshot snapshot = service.accountSummary();
        return Map.of("success", true, "snapshot", snapshot);
    }

    @PostMapping("/index-universe/refresh")
    public KiwoomUsIndexUniverseService.UniverseStatus refreshIndexUniverse() {
        indexUniverse.refresh();
        return indexUniverse.status();
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
