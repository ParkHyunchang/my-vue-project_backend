package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.entity.KiwoomStrategyRun;
import com.hyunchang.webapp.entity.KiwoomTradeProposal;
import com.hyunchang.webapp.entity.KiwoomWatchItem;
import com.hyunchang.webapp.repository.KiwoomStrategyRunRepository;
import com.hyunchang.webapp.repository.KiwoomTradeProposalRepository;
import com.hyunchang.webapp.repository.KiwoomWatchItemRepository;
import com.hyunchang.webapp.service.KiwoomAutoTradeState;
import com.hyunchang.webapp.service.KiwoomOrderSyncService;
import com.hyunchang.webapp.service.KiwoomProposalOrderService;
import com.hyunchang.webapp.service.KiwoomStrategyAuditService;
import com.hyunchang.webapp.service.KiwoomStrategyService;
import com.hyunchang.webapp.service.KiwoomStrategySettingsService;
import com.hyunchang.webapp.service.prompt.AiPromptCatalog;
import com.hyunchang.webapp.service.prompt.AiPromptService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    private final KiwoomWatchItemRepository watch;
    private final KiwoomStrategyRunRepository runs;
    private final KiwoomTradeProposalRepository proposals;
    private final KiwoomAutoTradeState state;
    private final KiwoomStrategyAuditService audit;
    private final KiwoomOrderSyncService orderSync;
    private final KiwoomStrategySettingsService settings;
    private final AiPromptService promptService;

    public KiwoomStrategyController(
            KiwoomStrategyService strategy,
            KiwoomProposalOrderService orders,
            KiwoomProperties props,
            KiwoomWatchItemRepository watch,
            KiwoomStrategyRunRepository runs,
            KiwoomTradeProposalRepository proposals,
            KiwoomAutoTradeState state,
            KiwoomStrategyAuditService audit,
            KiwoomOrderSyncService orderSync,
            KiwoomStrategySettingsService settings,
            AiPromptService promptService) {
        this.strategy = strategy;
        this.orders = orders;
        this.props = props;
        this.watch = watch;
        this.runs = runs;
        this.proposals = proposals;
        this.state = state;
        this.audit = audit;
        this.orderSync = orderSync;
        this.settings = settings;
        this.promptService = promptService;
    }

    @GetMapping("/watchlist")
    public List<KiwoomWatchItem> list() {
        return watch.findAll();
    }

    @PostMapping("/watchlist")
    public ResponseEntity<?> add(@RequestBody WatchRequest request) {
        if (request.stockCode() == null || !request.stockCode().matches("\\d{6}"))
            return ResponseEntity.badRequest().body(Map.of("message", "종목코드는 6자리 숫자여야 합니다."));
        if (watch.existsByStockCode(request.stockCode()))
            return ResponseEntity.status(409).body(Map.of("message", "이미 관심종목에 있습니다."));
        KiwoomWatchItem item = new KiwoomWatchItem();
        item.setStockCode(request.stockCode());
        item.setStockName(
                request.stockName() == null || request.stockName().isBlank()
                        ? request.stockCode()
                        : request.stockName());
        item.setNote(request.note());
        return ResponseEntity.ok(watch.save(item));
    }

    @DeleteMapping("/watchlist/{id}")
    public ResponseEntity<Void> remove(@PathVariable Long id) {
        watch.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/decide")
    public ResponseEntity<?> decide() {
        try {
            return ResponseEntity.ok(strategy.runDecision("MANUAL"));
        } catch (IllegalStateException e) {
            // 긴급 중지 또는 판단 중복 실행 — 서버 오류(500)가 아니라 상태 충돌(409)로 응답한다.
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
        return response(orders.cancel(id, request.quantity()));
    }

    @PostMapping("/orders/sync")
    public KiwoomOrderSyncService.SyncResult syncOrders() {
        return orderSync.sync();
    }

    @GetMapping("/settings")
    public Map<String, Object> settings() {
        var s = settings.current();
        return Map.of(
                "autoExecute",
                s.isAutoExecute(),
                "autoExecuteMinConfidence",
                s.getAutoExecuteMinConfidence(),
                "maxBuyDepositPercent",
                s.getMaxBuyDepositPercent(),
                "swingStopLossPercent",
                s.getSwingStopLossPercent(),
                "swingTakeProfitPercent",
                s.getSwingTakeProfitPercent(),
                "swingMaxHoldingDays",
                s.getSwingMaxHoldingDays(),
                "prompt",
                promptService.instruction(AiPromptCatalog.KIWOOM_TRADE_STRATEGY));
    }

    @PatchMapping("/settings")
    public Map<String, Object> updateSettings(@RequestBody StrategySettingsRequest request) {
        var s =
                settings.save(
                        new KiwoomStrategySettingsService.Update(
                                request.autoExecute(),
                                request.autoExecuteMinConfidence(),
                                request.maxBuyDepositPercent(),
                                request.swingStopLossPercent(),
                                request.swingTakeProfitPercent(),
                                request.swingMaxHoldingDays(),
                                request.prompt()),
                        "admin");
        audit.log("STRATEGY_SETTINGS_UPDATED", null, "Runtime strategy settings were updated.");
        return Map.of("success", true, "updatedAt", s.getUpdatedAt().toString());
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        // 정적 가드는 env(props), 런타임 조정값은 DB(settings)가 단일 출처다. autoExecute를 props에서 읽으면
        // 실제 동작(DB 값)과 화면 표시가 어긋날 수 있어 settings에서 읽는다.
        var s = settings.current();
        Map<String, Object> result = new HashMap<>();
        result.put("enabled", props.getStrategy().isEnabled());
        result.put("maxOrderAmount", props.getStrategy().getMaxOrderAmount());
        result.put("dailyMaxProposals", props.getStrategy().getDailyMaxProposals());
        result.put("cooldownMinutes", props.getStrategy().getCooldownMinutes());
        result.put("allowMarketOrders", props.getStrategy().isAllowMarketOrders());
        result.put("autoExecute", s.isAutoExecute());
        result.put("autoExecuteMinConfidence", s.getAutoExecuteMinConfidence());
        result.put("maxBuyDepositPercent", s.getMaxBuyDepositPercent());
        result.put("swingStopLossPercent", s.getSwingStopLossPercent());
        result.put("swingTakeProfitPercent", s.getSwingTakeProfitPercent());
        result.put("swingMaxHoldingDays", s.getSwingMaxHoldingDays());
        result.put("orderEnabled", props.isTradeEnabled());
        result.put("dryRun", !props.isTradeEnabled());
        return result;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "configured",
                props.isConfigured(),
                "autoTrading",
                state.isAutoTrading(),
                "emergencyStopped",
                state.isEmergencyStopped(),
                "decisionRunning",
                state.isDeciding(),
                "lastRunAt",
                state.getLastRunAt() == null ? "" : state.getLastRunAt().toString(),
                "runCount",
                runs.count(),
                "proposalCount",
                proposals.count(),
                "recentAudit",
                audit.recent());
    }

    @GetMapping("/runs")
    public List<Map<String, Object>> history(@RequestParam(defaultValue = "10") int limit) {
        List<KiwoomStrategyRun> list =
                runs.findByOrderByIdDesc(PageRequest.of(0, Math.min(Math.max(limit, 1), 50)))
                        .getContent();
        Map<Long, List<KiwoomTradeProposal>> grouped = new HashMap<>();
        for (KiwoomTradeProposal proposal :
                proposals.findByRunIdInOrderByIdAsc(
                        list.stream().map(KiwoomStrategyRun::getId).toList()))
            grouped.computeIfAbsent(proposal.getRun().getId(), key -> new ArrayList<>())
                    .add(proposal);
        return list.stream()
                .map(
                        run ->
                                Map.<String, Object>of(
                                        "id",
                                        run.getId(),
                                        "status",
                                        run.getStatus(),
                                        "triggeredBy",
                                        run.getTriggeredBy(),
                                        "marketView",
                                        run.getMarketView() == null ? "" : run.getMarketView(),
                                        "errorMessage",
                                        run.getErrorMessage() == null ? "" : run.getErrorMessage(),
                                        "createdAt",
                                        run.getCreatedAt().toString(),
                                        "proposals",
                                        grouped.getOrDefault(run.getId(), List.of())))
                .toList();
    }

    private ResponseEntity<?> response(KiwoomProposalOrderService.Result result) {
        return result.success()
                ? ResponseEntity.ok(result)
                : ResponseEntity.status(409).body(Map.of("message", result.message()));
    }

    public record WatchRequest(String stockCode, String stockName, String note) {}

    public record RejectRequest(String reason) {}

    public record ExecuteRequest(boolean confirmed) {}

    public record DraftUpdateRequest(int quantity, Long limitPrice) {}

    public record OrderAmendRequest(int quantity, Long limitPrice) {}

    public record CancelRequest(int quantity) {}

    public record StrategySettingsRequest(
            boolean autoExecute,
            int autoExecuteMinConfidence,
            double maxBuyDepositPercent,
            double swingStopLossPercent,
            double swingTakeProfitPercent,
            int swingMaxHoldingDays,
            String prompt) {}
}
