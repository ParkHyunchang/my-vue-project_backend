package com.hyunchang.webapp.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.service.KiwoomAccountHoldingSyncService;
import com.hyunchang.webapp.service.KiwoomAuthService;
import com.hyunchang.webapp.service.KiwoomPositionExitService;
import com.hyunchang.webapp.service.KiwoomStrategyAuditService;
import com.hyunchang.webapp.service.KiwoomStrategyService;
import com.hyunchang.webapp.service.KiwoomStrategySettingsService;
import com.hyunchang.webapp.service.KiwoomTradeService;
import com.hyunchang.webapp.service.kiwoom.KiwoomAutoTradeState;
import com.hyunchang.webapp.service.kiwoom.KiwoomWebsocketClient;
import com.hyunchang.webapp.util.KiwoomMarketHours;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(KiwoomAutoTradeController.class);
    private final KiwoomProperties properties;
    private final KiwoomAuthService authService;
    private final KiwoomTradeService tradeService;
    private final KiwoomWebsocketClient websocketClient;
    private final KiwoomAutoTradeState state;
    private final KiwoomStrategyService strategyService;
    private final KiwoomStrategySettingsService settings;
    private final KiwoomStrategyAuditService audit;
    private final KiwoomPositionExitService exits;
    private final KiwoomAccountHoldingSyncService accountHoldings;

    public KiwoomAutoTradeController(
            KiwoomProperties properties,
            KiwoomAuthService authService,
            KiwoomTradeService tradeService,
            KiwoomWebsocketClient websocketClient,
            KiwoomAutoTradeState state,
            KiwoomStrategyService strategyService,
            KiwoomStrategySettingsService settings,
            KiwoomStrategyAuditService audit,
            KiwoomPositionExitService exits,
            KiwoomAccountHoldingSyncService accountHoldings) {
        this.properties = properties;
        this.authService = authService;
        this.tradeService = tradeService;
        this.websocketClient = websocketClient;
        this.state = state;
        this.strategyService = strategyService;
        this.settings = settings;
        this.audit = audit;
        this.exits = exits;
        this.accountHoldings = accountHoldings;
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
                "orderEnabled",
                properties.isTradeEnabled(),
                "marketOpen",
                KiwoomMarketHours.isOpen(),
                "marketMessage",
                "장전·장후 시세는 표시하며, 자동매매 주문은 정규장(평일 09:00~15:30)에만 실행됩니다.",
                "consecutiveApiFailures",
                state.getConsecutiveApiFailures(),
                "lastApiFailureAt",
                state.getLastApiFailureAt() == null ? "" : state.getLastApiFailureAt().toString(),
                "lastApiFailureMessage",
                state.getLastApiFailureMessage() == null ? "" : state.getLastApiFailureMessage());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void resumeRealtimeSubscription() {
        if (properties.isConfigured() && state.isAutoTrading()) {
            websocketClient.connectAndSubscribe(strategyService.subscriptionCodes());
            exits.refreshPositions("APPLICATION_RESTART");
            audit.log("AUTOMATION_RESTORED", null, "재시작 후 저장된 자동매매 상태와 실시간 시세 구독을 복구했습니다.");
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUpAccessToken() {
        if (!properties.isConfigured()) return;
        authService
                .getAccessToken()
                .subscribe(
                        ignored -> log.info("Kiwoom access token is ready."),
                        error -> log.warn("Failed to warm up the Kiwoom access token.", error));
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
            // 이전 버전의 안전 중지 상태도 이제 하나의 자동주문 스위치로 복구한다.
            state.clearEmergencyStop();
            settings.activateFullAutomation();
            audit.log("FULL_AUTOMATION_ACTIVATED", null, "관리자 시작 요청으로 자동 주문 및 리스크 청산 루프를 활성화했습니다.");
            state.setAutoTrading(true);
            websocketClient.connectAndSubscribe(strategyService.subscriptionCodes());
            boolean exitsReady = exits.resumeExitManagement();
            websocketClient.publishEvent(
                    "system", "자동주문을 재개하고 현재 보유종목의 익절·손절 기준을 다시 계산했습니다.");
            return ResponseEntity.ok(
                    Map.of(
                            "success", true,
                            "autoTrading", true,
                            "exitManagementReady", exitsReady));
        }
        state.setAutoTrading(false);
        KiwoomPositionExitService.PauseResult paused = exits.pauseExitManagement();
        audit.log("FULL_AUTOMATION_STOPPED", null, "자동주문 완전 중지: 미체결 자동주문 취소 요청 " + paused.cancellationRequested() + "건");
        if (paused.cancellationFailed() > 0)
            websocketClient.publishEvent(
                    "error", "자동주문은 중지했지만 미체결 자동주문 " + paused.cancellationFailed() + "건의 취소 요청이 실패했습니다. 키움 주문을 확인하세요.");
        websocketClient.publishEvent(
                "system", "자동주문을 완전히 중지했습니다. 기존 미체결 자동주문도 취소하며 재개 전까지 신규 자동주문을 보내지 않습니다.");
        return ResponseEntity.ok(
                Map.of(
                        "success", true,
                        "autoTrading", false,
                        "orderCancellationRequested", paused.cancellationRequested(),
                        "orderCancellationFailed", paused.cancellationFailed()));
    }

    @GetMapping("/summary")
    public Mono<Map<String, Object>> summary() {
        return Mono.zip(tradeService.getDeposit(), tradeService.getBalance())
                .map(data -> {
                    KiwoomTradeService.AccountAsset totalAsset =
                            tradeService.accountAsset(data.getT1(), data.getT2());
                    KiwoomAutoTradeState.AssetChange assetChange =
                            state.assetChangeFromPreviousClose(totalAsset.amount());
                    Map<String, Object> summary = new LinkedHashMap<>();
                    summary.put("totalAsset", totalAsset.amount());
                    summary.put(
                            "totalAssetSource",
                            "추정예탁자산".equals(totalAsset.source())
                                    ? "키움 기준 · 결제 예정금액 포함"
                                    : "예수금 + 보유주식 평가액");
                    summary.put("totalAssetChange", assetChange == null ? null : assetChange.amount());
                    summary.put(
                            "totalAssetChangePercent",
                            assetChange == null ? null : assetChange.percent());
                    summary.put("deposit", number(data.getT1(), "entr"));
                    summary.put("d1Deposit", number(data.getT1(), "d1_entra"));
                    summary.put("d2Deposit", number(data.getT1(), "d2_entra"));
                    summary.put(
                            "orderAvailable",
                            number(data.getT1(), "ord_alow_amt", "ord_psbl_cash", "entr"));
                    summary.put("profitLoss", tradeService.totalEvaluationProfitLoss(data.getT2()));
                    summary.put("stockProfitRate", tradeService.totalEvaluationProfitRate(data.getT2()));
                    summary.put("totalEvaluation", tradeService.totalEvaluationAmount(data.getT2()));
                    return summary;
                });
    }

    /** 자동매매가 실제로 사용하는 키움 계좌 보유현황 스냅샷이다. */
    @GetMapping("/holdings")
    public List<Map<String, Object>> holdings() {
        return accountHoldings.currentHoldings().stream()
                .map(
                        holding ->
                                Map.<String, Object>of(
                                        "stockCode", holding.getStockCode(),
                                        "stockName", holding.getStockName(),
                                        "quantity", holding.getQuantity(),
                                        "sellableQuantity", holding.getSellableQuantity(),
                                        "averagePrice", holding.getAveragePrice(),
                                        "currentPrice", holding.getCurrentPrice(),
                                        "profitLossPercent", holding.getProfitLossPercent(),
                                        "positionOpenedAt",
                                                holding.getPositionOpenedAt() == null
                                                        ? ""
                                                        : holding.getPositionOpenedAt().toString(),
                                        "syncedAt", holding.getSyncedAt().toString()))
                .toList();
    }

    @PostMapping("/holdings/sync")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> syncHoldings() {
        var result = accountHoldings.sync("MANUAL_CHECK");
        return result.success()
                ? ResponseEntity.ok(result)
                : ResponseEntity.status(502).body(Map.of("message", result.message()));
    }

    /** 관리자 수동 시장가 청산. stockCodes가 비어 있으면 보유 전 종목이 대상이다. */
    @PostMapping("/holdings/liquidate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> liquidateHoldings(
            @RequestBody(required = false) LiquidateRequest request) {
        KiwoomPositionExitService.LiquidationResult result =
                exits.liquidate(request == null ? List.of() : request.stockCodes());
        if (!result.accepted())
            return ResponseEntity.status(409).body(Map.of("message", result.message()));
        audit.log("MANUAL_LIQUIDATION_COMPLETED", null, result.message());
        return ResponseEntity.ok(result);
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

    public record LiquidateRequest(List<String> stockCodes) {}
}
