package com.hyunchang.webapp.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.service.KiwoomAuthService;
import com.hyunchang.webapp.service.KiwoomAutoTradeState;
import com.hyunchang.webapp.service.KiwoomStrategyAuditService;
import com.hyunchang.webapp.service.KiwoomStrategyService;
import com.hyunchang.webapp.service.KiwoomStrategySettingsService;
import com.hyunchang.webapp.service.KiwoomTradeService;
import com.hyunchang.webapp.service.KiwoomWebsocketClient;
import java.util.Map;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Vue 자동매매 탭 전용 API입니다. 키·토큰·계좌번호 원문은 어떤 응답에도 포함하지 않습니다. */
@RestController
@RequestMapping("/api/kiwoom/auto-trade")
public class KiwoomAutoTradeController {
    private final KiwoomProperties properties;
    private final KiwoomAuthService authService;
    private final KiwoomTradeService tradeService;
    private final KiwoomWebsocketClient websocketClient;
    private final KiwoomAutoTradeState state;
    private final KiwoomStrategyService strategyService;
    private final KiwoomStrategySettingsService settings;
    private final KiwoomStrategyAuditService audit;

    public KiwoomAutoTradeController(
            KiwoomProperties properties,
            KiwoomAuthService authService,
            KiwoomTradeService tradeService,
            KiwoomWebsocketClient websocketClient,
            KiwoomAutoTradeState state,
            KiwoomStrategyService strategyService,
            KiwoomStrategySettingsService settings,
            KiwoomStrategyAuditService audit) {
        this.properties = properties;
        this.authService = authService;
        this.tradeService = tradeService;
        this.websocketClient = websocketClient;
        this.state = state;
        this.strategyService = strategyService;
        this.settings = settings;
        this.audit = audit;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "configured",
                properties.isConfigured(),
                "connected",
                websocketClient.isConnected(),
                "tokenValid",
                authService.hasUsableToken(),
                "autoTrading",
                state.isAutoTrading(),
                "emergencyStopped",
                state.isEmergencyStopped(),
                "orderEnabled",
                properties.isTradeEnabled(),
                "consecutiveApiFailures",
                state.getConsecutiveApiFailures(),
                "lastApiFailureAt",
                state.getLastApiFailureAt() == null ? "" : state.getLastApiFailureAt().toString(),
                "lastApiFailureMessage",
                state.getLastApiFailureMessage() == null ? "" : state.getLastApiFailureMessage());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void resumeRealtimeSubscription() {
        if (properties.isConfigured() && state.isAutoTrading() && !state.isEmergencyStopped()) {
            websocketClient.connectAndSubscribe(strategyService.subscriptionCodes());
            audit.log("AUTOMATION_RESTORED", null, "재시작 후 저장된 자동매매 상태와 실시간 시세 구독을 복구했습니다.");
        }
    }

    @PostMapping("/token/refresh")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<Map<String, Object>> refreshToken() {
        return authService
                .refreshToken()
                .map(state -> Map.of("success", true, "expiresAt", state.expiresAt().toString()));
    }

    @PostMapping("/control")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> control(@RequestBody ControlRequest request) {
        if (request.enabled() && state.isEmergencyStopped()) {
            return ResponseEntity.status(409)
                    .body(Map.of("success", false, "message", "긴급 중지 상태입니다. 먼저 재개를 명시적으로 실행하세요."));
        }
        if (request.enabled() && !properties.isConfigured()) {
            // 자동매매 ON은 'AI 판단 스케줄 시작'만 의미한다. 주문 전송은 KIWOOM_TRADE_ENABLED가 별도로 게이트한다.
            return ResponseEntity.status(409)
                    .body(
                            Map.of(
                                    "success",
                                    false,
                                    "message",
                                    "KIWOOM_APP_KEY / KIWOOM_SECRET_KEY 설정이 필요합니다."));
        }
        if (request.enabled() && !properties.isTradeEnabled()) {
            return ResponseEntity.status(409)
                    .body(
                            Map.of(
                                    "success",
                                    false,
                                    "message",
                                    "KIWOOM_TRADE_ENABLED=true 설정이 필요합니다."));
        }
        if (request.enabled()) {
            settings.activateFullAutomation();
            audit.log("FULL_AUTOMATION_ACTIVATED", null, "관리자 시작 요청으로 자동 주문 및 리스크 청산 루프를 활성화했습니다.");
        }
        state.setAutoTrading(request.enabled());
        if (request.enabled())
            websocketClient.connectAndSubscribe(strategyService.subscriptionCodes());
        return ResponseEntity.ok(Map.of("success", true, "autoTrading", state.isAutoTrading()));
    }

    @PostMapping("/emergency-stop")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> emergencyStop() {
        state.emergencyStop();
        websocketClient.publishEvent("system", "긴급 중지가 실행되었습니다. 자동 판단과 주문 전송을 중단합니다.");
        audit.log("EMERGENCY_STOP", null, "관리자가 긴급 중지를 실행했습니다.");
        return Map.of("success", true, "emergencyStopped", true);
    }

    @PostMapping("/emergency-resume")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> emergencyResume() {
        state.clearEmergencyStop();
        audit.log("EMERGENCY_RESUME", null, "관리자가 긴급 중지를 해제했습니다.");
        return Map.of("success", true, "emergencyStopped", false);
    }

    @GetMapping("/summary")
    public Mono<Map<String, Object>> summary() {
        return Mono.zip(tradeService.getDeposit(), tradeService.getBalance())
                .map(
                        data ->
                                Map.of(
                                        "deposit",
                                        number(data.getT1(), "entr", "ord_alow_amt"),
                                        "profitLoss",
                                        number(data.getT2(), "tot_evlt_pl", "evlt_pl_amt"),
                                        "totalEvaluation",
                                        number(data.getT2(), "tot_evlt_amt", "evlt_amt")));
    }

    @PostMapping("/orders")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<JsonNode> placeOrder(@RequestBody KiwoomTradeService.OrderRequest request) {
        return tradeService.placeOrder(request);
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> events() {
        return websocketClient
                .events()
                .map(event -> ServerSentEvent.builder(event).event("kiwoom").build());
    }

    private long number(JsonNode node, String... fields) {
        for (String field : fields) if (node.hasNonNull(field)) return node.path(field).asLong();
        return 0L;
    }

    public record ControlRequest(boolean enabled) {}
}
