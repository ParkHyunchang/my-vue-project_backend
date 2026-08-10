package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.dto.KiwoomStrategyRunResponse;
import com.hyunchang.webapp.service.KiwoomOrderSyncService;
import com.hyunchang.webapp.service.KiwoomPositionExitService;
import com.hyunchang.webapp.service.KiwoomProposalOrderService;
import com.hyunchang.webapp.service.KiwoomRiskManagerService;
import com.hyunchang.webapp.service.KiwoomStrategyAuditService;
import com.hyunchang.webapp.service.KiwoomStrategyHistoryService;
import com.hyunchang.webapp.service.KiwoomStrategyService;
import com.hyunchang.webapp.service.KiwoomStrategySettingsService;
import com.hyunchang.webapp.service.kiwoom.KiwoomAutoTradeState;
import com.hyunchang.webapp.service.prompt.AiPromptCatalog;
import com.hyunchang.webapp.service.prompt.AiPromptService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/kiwoom/strategy")
@PreAuthorize("hasRole('ADMIN')")
public class KiwoomStrategyController {
    private final KiwoomStrategyService strategy;
    private final KiwoomProposalOrderService orders;
    private final KiwoomProperties props;
    private final KiwoomStrategyHistoryService history;
    private final KiwoomAutoTradeState state;
    private final KiwoomStrategyAuditService audit;
    private final KiwoomOrderSyncService orderSync;
    private final KiwoomStrategySettingsService settings;
    private final AiPromptService promptService;
    private final KiwoomRiskManagerService risk;
    private final KiwoomPositionExitService positionExits;

    public KiwoomStrategyController(
            KiwoomStrategyService strategy,
            KiwoomProposalOrderService orders,
            KiwoomProperties props,
            KiwoomStrategyHistoryService history,
            KiwoomAutoTradeState state,
            KiwoomStrategyAuditService audit,
            KiwoomOrderSyncService orderSync,
            KiwoomStrategySettingsService settings,
            AiPromptService promptService,
            KiwoomRiskManagerService risk,
            KiwoomPositionExitService positionExits) {
        this.strategy = strategy;
        this.orders = orders;
        this.props = props;
        this.history = history;
        this.state = state;
        this.audit = audit;
        this.orderSync = orderSync;
        this.settings = settings;
        this.promptService = promptService;
        this.risk = risk;
        this.positionExits = positionExits;
    }

    /** 지금 유니버스에 편입된 KRX 자동 스캔 매매 후보 — 읽기 전용. 사람이 등록하는 관심종목은 없다. */
    @GetMapping("/universe")
    public List<Map<String, Object>> universe() {
        return strategy.currentSwingCandidates().stream()
                .map(
                        c ->
                                Map.<String, Object>of(
                                        "code", c.bareCode(),
                                        "name", c.name(),
                                        "market", c.market(),
                                        "closePrice", c.closePrice(),
                                        "changePercent", c.changePercent(),
                                        "volumeRatio", c.volumeRatio(),
                                        "asOf", c.asOf().toString()))
                .toList();
    }

    @PostMapping("/decide")
    public ResponseEntity<?> decide() {
        try {
            return ResponseEntity.ok(strategy.runDecision("MANUAL"));
        } catch (IllegalStateException e) {
            // 안전 자동중지 또는 판단 중복 실행 — 서버 오류(500)가 아니라 상태 충돌(409)로 응답한다.
            return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/risk/scan")
    public ResponseEntity<?> riskScan() {
        try {
            return ResponseEntity.ok(risk.runRiskScan("MANUAL"));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/risk/daily-loss/reset")
    public ResponseEntity<?> resetDailyLossGuard() {
        try {
            return ResponseEntity.ok(risk.resetDailyLossGuard());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/proposals/{id}/approve")
    public ResponseEntity<?> approve(@PathVariable long id) {
        return response(orders.approve(id));
    }

    @PostMapping("/proposals/{id}/reject")
    public ResponseEntity<?> reject(
            @PathVariable long id, @RequestBody(required = false) RejectRequest request) {
        return response(orders.reject(id, request == null ? null : request.reason()));
    }

    @PostMapping("/proposals/{id}/draft")
    public ResponseEntity<?> draft(@PathVariable long id) {
        return response(orders.draft(id));
    }

    @PostMapping("/proposals/{id}/execute")
    public ResponseEntity<?> execute(@PathVariable long id, @RequestBody ExecuteRequest request) {
        return response(orders.execute(id, request != null && request.confirmed()));
    }

    @PatchMapping("/proposals/{id}/draft")
    public ResponseEntity<?> updateDraft(
            @PathVariable long id, @RequestBody DraftUpdateRequest request) {
        return response(orders.updateDraft(id, request.quantity(), request.limitPrice()));
    }

    @PatchMapping("/proposals/{id}/order")
    public ResponseEntity<?> amendOrder(
            @PathVariable long id, @RequestBody OrderAmendRequest request) {
        return response(orders.amend(id, request.quantity(), request.limitPrice()));
    }

    @PostMapping("/proposals/{id}/cancel")
    public ResponseEntity<?> cancelOrder(
            @PathVariable long id, @RequestBody CancelRequest request) {
        return response(
                orders.cancel(id, request.quantity(), "사용자가 화면에서 수동으로 주문 취소를 요청했습니다."));
    }

    @PostMapping("/orders/sync")
    public KiwoomOrderSyncService.SyncResult syncOrders() {
        return orderSync.sync();
    }

    @GetMapping("/settings")
    public Map<String, Object> settings() {
        var s = settings.current();
        Map<String, Object> result = new HashMap<>();
        result.put("autoExecute", s.isAutoExecute());
        result.put("autoExecuteMinConfidence", s.getAutoExecuteMinConfidence());
        result.put("maxBuyDepositPercent", s.getMaxBuyDepositPercent());
        result.put("candidateReevaluationMinutes", s.getCandidateReevaluationMinutes());
        result.put("swingMinChangePercent", s.getSwingMinChangePercent());
        result.put("swingMaxChangePercent", s.getSwingMaxChangePercent());
        result.put("swingMinVolumeRatio", s.getSwingMinVolumeRatio());
        result.put("swingStopLossPercent", s.getSwingStopLossPercent());
        result.put("swingTakeProfitPercent", s.getSwingTakeProfitPercent());
        result.put("swingTakeProfitPercent2", s.getSwingTakeProfitPercent2());
        result.put("swingTakeProfitSplitPercent", s.getSwingTakeProfitSplitPercent());
        result.put("swingMaxHoldingDays", s.getSwingMaxHoldingDays());
        result.put(
                "maxOrderPriceDeviationPercent",
                props.getStrategy().getMaxOrderPriceDeviationPercent());
        result.put("defaultDailyLossPercent", props.getStrategy().getDefaultDailyLossPercent());
        result.put("riskLoopEnabled", s.isRiskLoopEnabled());
        result.put("dailyLossLimitPercent", s.getDailyLossLimitPercent());
        result.put("defaultDailyLossPercent", props.getStrategy().getDefaultDailyLossPercent());
        result.put("dailyMaxProposals", s.getDailyMaxProposals());
        result.put("prompt", promptService.instruction(AiPromptCatalog.KIWOOM_TRADE_STRATEGY));
        return result;
    }

    @PatchMapping("/settings")
    public Map<String, Object> updateSettings(@RequestBody StrategySettingsRequest request) {
        double previousDailyLossLimit = settings.current().getDailyLossLimitPercent();
        var s =
                settings.save(
                        new KiwoomStrategySettingsService.Update(
                                request.autoExecute(),
                                request.autoExecuteMinConfidence(),
                                request.maxBuyDepositPercent(),
                                request.candidateReevaluationMinutes(),
                                request.swingMinChangePercent(),
                                request.swingMaxChangePercent(),
                                request.swingMinVolumeRatio(),
                                request.swingStopLossPercent(),
                                request.swingTakeProfitPercent(),
                                request.swingTakeProfitPercent2(),
                                request.swingTakeProfitSplitPercent(),
                                request.swingMaxHoldingDays(),
                                request.riskLoopEnabled(),
                                request.dailyLossLimitPercent(),
                                request.dailyMaxProposals(),
                                request.prompt()),
                        "admin");
        audit.log("STRATEGY_SETTINGS_UPDATED", null, "Runtime strategy settings were updated.");
        var applied = positionExits.applyChangedSettings();
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("updatedAt", s.getUpdatedAt().toString());
        result.put("exitSettingsApplied", applied.completed());
        result.put("applyMessage", applied.message());
        if (Double.compare(previousDailyLossLimit, s.getDailyLossLimitPercent()) != 0)
            result.put(
                    "dailyLossApplyMessage",
                    risk.applyChangedDailyLossLimit(s.getDailyLossLimitPercent()));
        return result;
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        // 정적 가드는 env(props), 런타임 조정값은 DB(settings)가 단일 출처다. autoExecute를 props에서 읽으면
        // 실제 동작(DB 값)과 화면 표시가 어긋날 수 있어 settings에서 읽는다.
        var s = settings.current();
        Map<String, Object> result = new HashMap<>();
        result.put("enabled", props.getStrategy().isEnabled());
        result.put("maxOrderAmount", props.getStrategy().getMaxOrderAmount());
        result.put("dailyMaxProposals", s.getDailyMaxProposals());
        result.put("cooldownMinutes", props.getStrategy().getCooldownMinutes());
        result.put("allowMarketOrders", props.getStrategy().isAllowMarketOrders());
        result.put("autoExecute", s.isAutoExecute());
        result.put("autoExecuteMinConfidence", s.getAutoExecuteMinConfidence());
        result.put("maxBuyDepositPercent", s.getMaxBuyDepositPercent());
        result.put("candidateReevaluationMinutes", s.getCandidateReevaluationMinutes());
        result.put("swingMinChangePercent", s.getSwingMinChangePercent());
        result.put("swingMaxChangePercent", s.getSwingMaxChangePercent());
        result.put("swingMinVolumeRatio", s.getSwingMinVolumeRatio());
        result.put("swingStopLossPercent", s.getSwingStopLossPercent());
        result.put("swingTakeProfitPercent", s.getSwingTakeProfitPercent());
        result.put("swingTakeProfitPercent2", s.getSwingTakeProfitPercent2());
        result.put("swingTakeProfitSplitPercent", s.getSwingTakeProfitSplitPercent());
        result.put("swingMaxHoldingDays", s.getSwingMaxHoldingDays());
        result.put("riskLoopEnabled", s.isRiskLoopEnabled());
        result.put("dailyLossLimitPercent", s.getDailyLossLimitPercent());
        result.put("orderEnabled", props.isTradeEnabled());
        return result;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new HashMap<>();
        result.put("configured", props.isConfigured());
        result.put("autoTrading", state.isAutoTrading());
        result.put("decisionRunning", state.isDeciding());
        result.put("consecutiveApiFailures", state.getConsecutiveApiFailures());
        result.put(
                "lastApiFailureAt",
                state.getLastApiFailureAt() == null ? "" : state.getLastApiFailureAt().toString());
        result.put(
                "lastApiFailureMessage",
                state.getLastApiFailureMessage() == null ? "" : state.getLastApiFailureMessage());
        result.put(
                "lastRunAt", state.getLastRunAt() == null ? "" : state.getLastRunAt().toString());
        result.put("runCount", history.runCount());
        result.put("proposalCount", history.proposalCount());
        result.put("risk", riskStatus());
        result.put("recentAudit", audit.recent());
        return result;
    }

    private Map<String, Object> riskStatus() {
        var s = settings.current();
        Map<String, Object> result = new HashMap<>();
        result.put("riskLoopEnabled", s.isRiskLoopEnabled());
        result.put("dailyLossLimitPercent", s.getDailyLossLimitPercent());
        result.put(
                "lastScanAt", risk.getLastScanAt() == null ? "" : risk.getLastScanAt().toString());
        KiwoomAutoTradeState.DailyLossStatus loss = state.dailyLossStatus();
        result.put("triggered", state.isDailyLossTriggered());
        result.put("snapshotDate", loss == null ? "" : loss.snapshotDate().toString());
        result.put("baseAsset", loss == null ? 0 : loss.baseAsset());
        result.put("adjustedBaseAsset", loss == null ? 0 : loss.adjustedBaseAsset());
        result.put("netCashFlow", loss == null ? 0 : loss.netCashFlow() - loss.baseNetCashFlow());
        result.put("lastAsset", loss == null ? 0 : loss.lastAsset());
        result.put("drawdown", loss == null ? 0 : loss.drawdown());
        result.put("drawdownPercent", loss == null ? 0 : loss.drawdownPercent());
        result.put(
                "lastCheckedAt",
                loss == null || loss.lastCheckedAt() == null
                        ? ""
                        : loss.lastCheckedAt().toString());
        return result;
    }

    @GetMapping("/runs")
    public List<KiwoomStrategyRunResponse> runs(@RequestParam(defaultValue = "10") int limit) {
        return history.recentRuns(limit);
    }

    private ResponseEntity<?> response(KiwoomProposalOrderService.Result result) {
        return result.success()
                ? ResponseEntity.ok(result)
                : ResponseEntity.status(409).body(Map.of("message", result.message()));
    }

    public record RejectRequest(String reason) {}

    public record ExecuteRequest(boolean confirmed) {}

    public record DraftUpdateRequest(int quantity, Long limitPrice) {}

    public record OrderAmendRequest(int quantity, Long limitPrice) {}

    public record CancelRequest(int quantity) {}

    public record StrategySettingsRequest(
            boolean autoExecute,
            int autoExecuteMinConfidence,
            double maxBuyDepositPercent,
            int candidateReevaluationMinutes,
            double swingMinChangePercent,
            double swingMaxChangePercent,
            double swingMinVolumeRatio,
            double swingStopLossPercent,
            double swingTakeProfitPercent,
            double swingTakeProfitPercent2,
            double swingTakeProfitSplitPercent,
            int swingMaxHoldingDays,
            boolean riskLoopEnabled,
            double dailyLossLimitPercent,
            int dailyMaxProposals,
            String prompt) {}
}
