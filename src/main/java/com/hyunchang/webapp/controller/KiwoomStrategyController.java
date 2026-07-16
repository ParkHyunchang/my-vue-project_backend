package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.entity.KiwoomStrategyRun;
import com.hyunchang.webapp.entity.KiwoomTradeProposal;
import com.hyunchang.webapp.entity.KiwoomWatchItem;
import com.hyunchang.webapp.repository.KiwoomStrategyRunRepository;
import com.hyunchang.webapp.repository.KiwoomTradeProposalRepository;
import com.hyunchang.webapp.repository.KiwoomWatchItemRepository;
import com.hyunchang.webapp.service.KiwoomProposalOrderService;
import com.hyunchang.webapp.service.KiwoomStrategyService;
import com.hyunchang.webapp.service.KiwoomAutoTradeState;
import com.hyunchang.webapp.service.KiwoomStrategyAuditService;
import com.hyunchang.webapp.service.KiwoomOrderSyncService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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

    public KiwoomStrategyController(
            KiwoomStrategyService strategy,
            KiwoomProposalOrderService orders,
            KiwoomProperties props,
            KiwoomWatchItemRepository watch,
            KiwoomStrategyRunRepository runs,
            KiwoomTradeProposalRepository proposals,
            KiwoomAutoTradeState state,
            KiwoomStrategyAuditService audit,
            KiwoomOrderSyncService orderSync) {
        this.strategy = strategy;
        this.orders = orders;
        this.props = props;
        this.watch = watch;
        this.runs = runs;
        this.proposals = proposals;
        this.state = state;
        this.audit = audit;
        this.orderSync = orderSync;
    }

    @GetMapping("/watchlist")
    public List<KiwoomWatchItem> list() { return watch.findAll(); }

    @PostMapping("/watchlist")
    public ResponseEntity<?> add(@RequestBody WatchRequest request) {
        if (request.stockCode() == null || !request.stockCode().matches("\\d{6}")) return ResponseEntity.badRequest().body(Map.of("message", "종목코드는 6자리 숫자여야 합니다."));
        if (watch.existsByStockCode(request.stockCode())) return ResponseEntity.status(409).body(Map.of("message", "이미 관심종목에 있습니다."));
        KiwoomWatchItem item = new KiwoomWatchItem(); item.setStockCode(request.stockCode()); item.setStockName(request.stockName() == null || request.stockName().isBlank() ? request.stockCode() : request.stockName()); item.setNote(request.note());
        return ResponseEntity.ok(watch.save(item));
    }

    @DeleteMapping("/watchlist/{id}")
    public ResponseEntity<Void> remove(@PathVariable Long id) { watch.deleteById(id); return ResponseEntity.noContent().build(); }

    @PostMapping("/decide")
    public KiwoomStrategyService.DecisionResult decide() { return strategy.runDecision("MANUAL"); }

    @PostMapping("/proposals/{id}/approve")
    public ResponseEntity<?> approve(@PathVariable long id) { return response(orders.approve(id)); }

    @PostMapping("/proposals/{id}/reject")
    public ResponseEntity<?> reject(@PathVariable long id, @RequestBody(required = false) RejectRequest request) { return response(orders.reject(id, request == null ? null : request.reason())); }

    @PostMapping("/proposals/{id}/draft")
    public ResponseEntity<?> draft(@PathVariable long id) { return response(orders.draft(id)); }

    @PostMapping("/proposals/{id}/execute")
    public ResponseEntity<?> execute(@PathVariable long id, @RequestBody ExecuteRequest request) { return response(orders.execute(id, request != null && request.confirmed())); }

    @PatchMapping("/proposals/{id}/draft")
    public ResponseEntity<?> updateDraft(@PathVariable long id, @RequestBody DraftUpdateRequest request) { return response(orders.updateDraft(id, request.quantity(), request.limitPrice())); }

    @PatchMapping("/proposals/{id}/order")
    public ResponseEntity<?> amendOrder(@PathVariable long id, @RequestBody OrderAmendRequest request) { return response(orders.amend(id, request.quantity(), request.limitPrice())); }

    @PostMapping("/proposals/{id}/cancel")
    public ResponseEntity<?> cancelOrder(@PathVariable long id, @RequestBody CancelRequest request) { return response(orders.cancel(id, request.quantity())); }

    @PostMapping("/orders/sync")
    public KiwoomOrderSyncService.SyncResult syncOrders() { return orderSync.sync(); }

    @GetMapping("/config")
    public Map<String, Object> config() { return Map.of("enabled", props.getStrategy().isEnabled(), "maxOrderAmount", props.getStrategy().getMaxOrderAmount(), "dailyMaxProposals", props.getStrategy().getDailyMaxProposals(), "cooldownMinutes", props.getStrategy().getCooldownMinutes(), "allowMarketOrders", props.getStrategy().isAllowMarketOrders(), "autoExecute", props.getStrategy().isAutoExecute(), "autoExecuteMinConfidence", props.getStrategy().getAutoExecuteMinConfidence(), "orderEnabled", props.isTradeEnabled(), "dryRun", !props.isTradeEnabled()); }

    @GetMapping("/health")
    public Map<String, Object> health() { return Map.of("configured", props.isConfigured(), "autoTrading", state.isAutoTrading(), "emergencyStopped", state.isEmergencyStopped(), "decisionRunning", state.isDeciding(), "lastRunAt", state.getLastRunAt() == null ? "" : state.getLastRunAt().toString(), "runCount", runs.count(), "proposalCount", proposals.count(), "recentAudit", audit.recent()); }

    @GetMapping("/runs")
    public List<Map<String, Object>> history(@RequestParam(defaultValue = "10") int limit) {
        List<KiwoomStrategyRun> list = runs.findByOrderByIdDesc(PageRequest.of(0, Math.min(Math.max(limit, 1), 50))).getContent();
        Map<Long, List<KiwoomTradeProposal>> grouped = new HashMap<>();
        for (KiwoomTradeProposal proposal : proposals.findByRunIdInOrderByIdAsc(list.stream().map(KiwoomStrategyRun::getId).toList())) grouped.computeIfAbsent(proposal.getRun().getId(), key -> new ArrayList<>()).add(proposal);
        return list.stream().map(run -> Map.<String, Object>of("id", run.getId(), "status", run.getStatus(), "triggeredBy", run.getTriggeredBy(), "marketView", run.getMarketView() == null ? "" : run.getMarketView(), "errorMessage", run.getErrorMessage() == null ? "" : run.getErrorMessage(), "createdAt", run.getCreatedAt().toString(), "proposals", grouped.getOrDefault(run.getId(), List.of()))).toList();
    }

    private ResponseEntity<?> response(KiwoomProposalOrderService.Result result) { return result.success() ? ResponseEntity.ok(result) : ResponseEntity.status(409).body(Map.of("message", result.message())); }
    public record WatchRequest(String stockCode, String stockName, String note) {}
    public record RejectRequest(String reason) {}
    public record ExecuteRequest(boolean confirmed) {}
    public record DraftUpdateRequest(int quantity, Long limitPrice) {}
    public record OrderAmendRequest(int quantity, Long limitPrice) {}
    public record CancelRequest(int quantity) {}
}
