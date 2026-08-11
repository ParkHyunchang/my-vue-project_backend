package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.entity.KiwoomUsAccountHolding;
import com.hyunchang.webapp.entity.KiwoomUsStrategyRun;
import com.hyunchang.webapp.entity.KiwoomUsStrategySettings;
import com.hyunchang.webapp.entity.KiwoomUsTradeProposal;
import com.hyunchang.webapp.repository.KiwoomUsAccountHoldingRepository;
import com.hyunchang.webapp.repository.KiwoomUsStrategyRunRepository;
import com.hyunchang.webapp.repository.KiwoomUsTradeProposalRepository;
import com.hyunchang.webapp.service.KiwoomUsTradeService.Holding;
import com.hyunchang.webapp.service.KiwoomUsTradeService.RankedStock;
import com.hyunchang.webapp.service.KiwoomUsTradeService.UsdCash;
import com.hyunchang.webapp.service.kiwoom.KiwoomUsAutoTradeState;
import com.hyunchang.webapp.util.KiwoomUsMarketHours;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class KiwoomUsAutoTradeService {
    private static final Duration API_TIMEOUT = Duration.ofSeconds(15);
    private static final Set<KiwoomUsTradeProposal.Status> OPEN_STATUSES =
            Set.of(
                    KiwoomUsTradeProposal.Status.ORDERED,
                    KiwoomUsTradeProposal.Status.PARTIALLY_FILLED,
                    KiwoomUsTradeProposal.Status.CANCEL_REQUESTED,
                    KiwoomUsTradeProposal.Status.UNKNOWN);

    private final KiwoomProperties properties;
    private final KiwoomUsTradeService trade;
    private final KiwoomUsStrategySettingsService settingsService;
    private final KiwoomUsAutoTradeState state;
    private final KiwoomUsAccountHoldingRepository holdingRepository;
    private final KiwoomUsTradeProposalRepository proposalRepository;
    private final KiwoomUsStrategyRunRepository runRepository;
    private final KiwoomUsAuditService audit;
    private final KiwoomUsEventService events;
    private final AtomicReference<List<Candidate>> lastCandidates =
            new AtomicReference<>(List.of());

    public KiwoomUsAutoTradeService(
            KiwoomProperties properties,
            KiwoomUsTradeService trade,
            KiwoomUsStrategySettingsService settingsService,
            KiwoomUsAutoTradeState state,
            KiwoomUsAccountHoldingRepository holdingRepository,
            KiwoomUsTradeProposalRepository proposalRepository,
            KiwoomUsStrategyRunRepository runRepository,
            KiwoomUsAuditService audit,
            KiwoomUsEventService events) {
        this.properties = properties;
        this.trade = trade;
        this.settingsService = settingsService;
        this.state = state;
        this.holdingRepository = holdingRepository;
        this.proposalRepository = proposalRepository;
        this.runRepository = runRepository;
        this.audit = audit;
        this.events = events;
    }

    @Scheduled(cron = "0 */10 * * * *")
    public void scheduledDecision() {
        if (properties.getUs().isStrategyEnabled() && state.isAutoTrading()) decide("SCHEDULE");
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 20_000)
    public void scheduledAccountAndExitSync() {
        if (!properties.isConfigured() || !state.isAutoTrading() || !KiwoomUsMarketHours.isOpen())
            return;
        try {
            reconcileOrders();
            syncHoldings();
            evaluateExits();
        } catch (RuntimeException error) {
            log("ERROR", null, "계좌/주문 동기화 실패: " + safe(error));
        }
    }

    public DecisionResult decide(String triggeredBy) {
        if (!state.tryStartDecision())
            return new DecisionResult("BUSY", "이미 후보를 산출 중입니다.", 0, null);
        KiwoomUsStrategyRun run = new KiwoomUsStrategyRun();
        run.setTriggeredBy(triggeredBy);
        try {
            validateDecisionReady();
            KiwoomUsStrategySettings settings = settingsService.current();
            AccountSnapshot snapshot = refreshAccountSnapshot();
            if (state.checkDailyLoss(
                    snapshot.totalAssetUsd().doubleValue(), settings.getDailyLossLimitPercent())) {
                throw new IllegalStateException("미국계좌 일일 손실 한도에 도달해 신규 매수를 중지했습니다.");
            }
            List<Candidate> candidates =
                    filterCandidates(
                            trade.getTradeValueTop().block(API_TIMEOUT), settings, snapshot);
            lastCandidates.set(candidates);
            for (Candidate candidate : candidates) {
                log(
                        "CANDIDATE",
                        null,
                        "후보 "
                                + candidate.symbol()
                                + "("
                                + candidate.name()
                                + ") $"
                                + candidate.price()
                                + ", 등락 "
                                + format(candidate.changePercent())
                                + "%"
                                + ", 거래량비 "
                                + format(candidate.volumeRatio())
                                + "배");
            }
            run.setCandidateSummary(
                    candidates.stream().limit(10).map(Candidate::symbol).toList().toString());
            if (candidates.isEmpty())
                return finishRun(run, "NO_CANDIDATE", "7개 조건을 모두 통과한 후보가 없습니다.", 0, null);
            if (!settings.isAutoExecute())
                return finishRun(
                        run, "CANDIDATE_ONLY", "후보만 기록했습니다(자동주문 설정 꺼짐).", candidates.size(), null);
            if (!state.isAutoTrading())
                return finishRun(run, "PAUSED", "후보만 기록했습니다(자동매매 꺼짐).", candidates.size(), null);

            KiwoomUsTradeProposal proposal = submitBuy(candidates.getFirst(), settings, snapshot);
            return finishRun(
                    run, "ORDERED", "미국주식 매수 주문을 전송했습니다.", candidates.size(), proposal.getId());
        } catch (RuntimeException error) {
            log("ERROR", null, "후보 산출/매수 중지: " + safe(error));
            return finishRun(run, "SKIPPED", safe(error), 0, null);
        } finally {
            state.finishDecision();
        }
    }

    public AccountSnapshot refreshAccountSnapshot() {
        JsonNode deposit = trade.getDepositDetail().block(API_TIMEOUT);
        JsonNode balance = trade.getBalance().block(API_TIMEOUT);
        UsdCash cash = trade.usdCash(deposit);
        syncHoldings(balance);
        BigDecimal invested = trade.totalEvaluation(balance);
        return new AccountSnapshot(
                cash,
                invested,
                cash.availableUsd().add(invested),
                holdingRepository.findByActiveTrueOrderByIdAsc().size());
    }

    public void syncHoldings() {
        syncHoldings(trade.getBalance().block(API_TIMEOUT));
    }

    public List<Candidate> candidates() {
        return lastCandidates.get();
    }

    public List<KiwoomUsAccountHolding> holdings() {
        return holdingRepository.findByActiveTrueOrderByIdAsc();
    }

    public List<KiwoomUsTradeProposal> proposals() {
        return proposalRepository.findTop50ByOrderByIdDesc();
    }

    private void validateDecisionReady() {
        if (!properties.isConfigured())
            throw new IllegalStateException("키움 App Key/Secret/계좌번호 설정이 필요합니다.");
        if (!properties.getUs().isStrategyEnabled())
            throw new IllegalStateException("미국주식 전략이 비활성화되어 있습니다.");
        if (!KiwoomUsMarketHours.isEntryWindow())
            throw new IllegalStateException("미국 정규장 진입 시간(10:00 ET 이후)이 아닙니다.");
        if (state.isEmergencyStopped()) throw new IllegalStateException("API 오류 안전정지가 걸려 있습니다.");
    }

    private List<Candidate> filterCandidates(
            List<RankedStock> ranked, KiwoomUsStrategySettings settings, AccountSnapshot account) {
        if (ranked == null) return List.of();
        Set<String> held = new HashSet<>();
        for (KiwoomUsAccountHolding holding : holdings()) held.add(holding.getSymbol());
        LocalDateTime cooldown = LocalDateTime.now().minusDays(settings.getSymbolCooldownDays());
        BigDecimal cashLimit =
                account.cash().availableUsd().min(BigDecimal.valueOf(settings.getMaxOrderUsd()));
        List<Candidate> result = new ArrayList<>();
        for (RankedStock stock : ranked) {
            boolean liquidUniverse =
                    stock.rank() > 0 && stock.rank() <= 50 && stock.tradedValue().signum() > 0;
            boolean momentum =
                    stock.changePercent() >= settings.getMinChangePercent()
                            && stock.changePercent() <= settings.getMaxChangePercent();
            boolean volume = stock.volumeRatio() >= settings.getMinVolumeRatio();
            boolean affordable = stock.currentPrice().compareTo(cashLimit) <= 0;
            boolean notHeld = !held.contains(stock.symbol());
            boolean cooldownPassed =
                    !proposalRepository.existsBySymbolAndActionAndOrderedAtAfter(
                            stock.symbol(), KiwoomUsTradeProposal.Action.BUY, cooldown);
            boolean riskCapacity =
                    account.positionCount() < settings.getMaxPositions()
                            && account.investedUsd()
                                            .compareTo(
                                                    BigDecimal.valueOf(
                                                            settings.getMaxAllocatedUsd()))
                                    < 0;
            if (liquidUniverse
                    && momentum
                    && volume
                    && affordable
                    && notHeld
                    && cooldownPassed
                    && riskCapacity) {
                result.add(
                        new Candidate(
                                stock.rank(),
                                stock.exchange(),
                                stock.symbol(),
                                stock.name(),
                                stock.currentPrice(),
                                stock.changePercent(),
                                stock.volumeRatio(),
                                stock.tradedValue()));
            }
        }
        return result;
    }

    private KiwoomUsTradeProposal submitBuy(
            Candidate candidate, KiwoomUsStrategySettings settings, AccountSnapshot account) {
        long dailyBuys = dailyBuys();
        if (dailyBuys >= settings.getDailyMaxBuys())
            throw new IllegalStateException("오늘 미국주식 매수 횟수 한도에 도달했습니다.");
        BigDecimal remainingAllocation =
                BigDecimal.valueOf(settings.getMaxAllocatedUsd())
                        .subtract(account.investedUsd())
                        .max(BigDecimal.ZERO);
        BigDecimal orderBudget =
                account.cash()
                        .availableUsd()
                        .min(BigDecimal.valueOf(settings.getMaxOrderUsd()))
                        .min(remainingAllocation);
        int quantity = orderBudget.divide(candidate.price(), 0, RoundingMode.DOWN).intValue();
        if (quantity < 1) throw new IllegalStateException("D+0 USD 외화예수금으로 1주를 살 수 없습니다.");

        KiwoomUsTradeProposal proposal = new KiwoomUsTradeProposal();
        proposal.setAction(KiwoomUsTradeProposal.Action.BUY);
        proposal.setExchange(candidate.exchange());
        proposal.setSymbol(candidate.symbol());
        proposal.setStockName(candidate.name());
        proposal.setQuantity(quantity);
        proposal.setLimitPrice(candidate.price());
        proposal.setReason("7조건 통과; 자금원=" + KiwoomUsTradeService.USD_CASH_SOURCE);
        proposal = proposalRepository.save(proposal);
        try {
            JsonNode response =
                    trade.placeOrder(
                                    new KiwoomUsTradeService.Order(
                                            "BUY",
                                            candidate.exchange(),
                                            candidate.symbol(),
                                            quantity,
                                            candidate.price(),
                                            false))
                            .block(API_TIMEOUT);
            String orderNo = text(response, "ord_no", "order_no");
            if (orderNo.isBlank()) {
                proposal.unknown(response == null ? "null" : response.toString());
                proposalRepository.save(proposal);
                state.emergencyStop("매수 응답에 주문번호가 없어 중복주문 방지를 위해 정지했습니다.");
                throw new IllegalStateException("주문번호 확인 실패: 키움 미체결 주문을 확인하세요.");
            }
            proposal.ordered(orderNo, response.toString());
            proposalRepository.save(proposal);
            log(
                    "BUY_ORDER",
                    proposal.getId(),
                    "매수 주문 "
                            + candidate.symbol()
                            + " "
                            + quantity
                            + "주 × $"
                            + candidate.price()
                            + " (USD 예수금만 사용, 주문번호 "
                            + orderNo
                            + ")");
            return proposal;
        } catch (RuntimeException error) {
            if (proposal.getStatus() == KiwoomUsTradeProposal.Status.PROPOSED) {
                proposal.failed(safe(error));
                proposalRepository.save(proposal);
            }
            throw error;
        }
    }

    private void evaluateExits() {
        KiwoomUsStrategySettings settings = settingsService.current();
        for (KiwoomUsAccountHolding holding : holdings()) {
            if (!holding.isManagedByAutoTrade()
                    || holding.getSellableQuantity() <= 0
                    || hasOpenSell(holding.getSymbol())) continue;
            double pnl = holding.getProfitLossPercent();
            String reason = null;
            boolean market = false;
            int quantity = holding.getSellableQuantity();
            if (pnl <= -settings.getStopLossPercent()) {
                reason = "손절 " + format(pnl) + "%";
                market = true;
            } else if (pnl >= settings.getTakeProfitPercent2()) {
                reason = "2차 익절 " + format(pnl) + "%";
            } else if (pnl >= settings.getTakeProfitPercent()) {
                reason = "1차 익절 " + format(pnl) + "%";
                quantity = Math.max(1, quantity / 2);
            } else if (holding.getPositionOpenedAt() != null
                    && holding.getPositionOpenedAt()
                            .isBefore(
                                    LocalDateTime.now().minusDays(settings.getMaxHoldingDays()))) {
                reason = "최대 보유기간 " + settings.getMaxHoldingDays() + "일 도달";
                market = true;
            }
            if (reason != null) submitSell(holding, quantity, market, reason);
        }
    }

    private void submitSell(
            KiwoomUsAccountHolding holding, int quantity, boolean market, String reason) {
        KiwoomUsTradeProposal proposal = new KiwoomUsTradeProposal();
        proposal.setAction(KiwoomUsTradeProposal.Action.SELL);
        proposal.setExchange(holding.getExchange());
        proposal.setSymbol(holding.getSymbol());
        proposal.setStockName(holding.getStockName());
        proposal.setQuantity(quantity);
        proposal.setLimitPrice(market ? null : holding.getCurrentPrice());
        proposal.setReason(reason);
        proposal = proposalRepository.save(proposal);
        try {
            JsonNode response =
                    trade.placeOrder(
                                    new KiwoomUsTradeService.Order(
                                            "SELL",
                                            holding.getExchange(),
                                            holding.getSymbol(),
                                            quantity,
                                            holding.getCurrentPrice(),
                                            market))
                            .block(API_TIMEOUT);
            String orderNo = text(response, "ord_no", "order_no");
            if (orderNo.isBlank()) {
                proposal.unknown(response == null ? "null" : response.toString());
                proposalRepository.save(proposal);
                state.emergencyStop("매도 응답에 주문번호가 없어 중복주문 방지를 위해 정지했습니다.");
                return;
            }
            proposal.ordered(orderNo, response.toString());
            proposalRepository.save(proposal);
            log(
                    "SELL_ORDER",
                    proposal.getId(),
                    "매도 주문 "
                            + holding.getSymbol()
                            + " "
                            + quantity
                            + "주 ("
                            + reason
                            + ", 주문번호 "
                            + orderNo
                            + ")");
        } catch (RuntimeException error) {
            if (proposal.getStatus() == KiwoomUsTradeProposal.Status.PROPOSED)
                proposal.failed(safe(error));
            proposalRepository.save(proposal);
            log("ERROR", proposal.getId(), "매도 주문 실패 " + holding.getSymbol() + ": " + safe(error));
        }
    }

    public void reconcileOrders() {
        List<KiwoomUsTradeProposal> open = proposalRepository.findByStatusIn(OPEN_STATUSES);
        if (open.isEmpty()) return;
        JsonNode fills = trade.getTodayFills().block(API_TIMEOUT);
        JsonNode unfilled = trade.getOpenOrders().block(API_TIMEOUT);
        List<JsonNode> records = new ArrayList<>();
        collectObjects(fills, records);
        collectObjects(unfilled, records);
        for (KiwoomUsTradeProposal proposal : open) {
            if (proposal.getBrokerOrderNo() == null || proposal.getBrokerOrderNo().isBlank())
                continue;
            boolean matchedOpenOrder = false;
            for (JsonNode record : records) {
                if (!proposal.getBrokerOrderNo()
                        .equals(text(record, "ord_no", "order_no", "orig_ord_no"))) continue;
                int filled = integer(record, "cntr_qty", "filled_qty", "exec_qty");
                int remaining =
                        integer(record, "ord_remnq", "rmn_qty", "unfilled_qty", "ord_remn_qty");
                if (remaining > 0) matchedOpenOrder = true;
                if (filled == 0 && remaining == 0) continue;
                KiwoomUsTradeProposal.Status before = proposal.getStatus();
                proposal.syncFill(
                        filled,
                        remaining,
                        decimal(record, "cntr_uv", "avg_cntr_pric", "exec_pric"));
                proposalRepository.save(proposal);
                if (before != KiwoomUsTradeProposal.Status.FILLED
                        && proposal.getStatus() == KiwoomUsTradeProposal.Status.FILLED) {
                    String type =
                            proposal.getAction() == KiwoomUsTradeProposal.Action.BUY
                                    ? "BUY_FILLED"
                                    : "SELL_FILLED";
                    log(
                            type,
                            proposal.getId(),
                            (proposal.getAction() == KiwoomUsTradeProposal.Action.BUY ? "매수" : "매도")
                                    + " 체결 "
                                    + proposal.getSymbol()
                                    + " "
                                    + proposal.getFilledQuantity()
                                    + "주");
                }
            }
            if (matchedOpenOrder
                    && proposal.getAction() == KiwoomUsTradeProposal.Action.BUY
                    && proposal.getStatus() == KiwoomUsTradeProposal.Status.ORDERED
                    && proposal.getOrderedAt() != null
                    && proposal.getOrderedAt().isBefore(LocalDateTime.now().minusSeconds(90))) {
                trade.cancelOrder(
                                proposal.getExchange(),
                                proposal.getSymbol(),
                                proposal.getBrokerOrderNo(),
                                Math.max(1, proposal.getRemainingQuantity()))
                        .block(API_TIMEOUT);
                proposal.requestCancel();
                proposalRepository.save(proposal);
                log(
                        "BUY_CANCEL",
                        proposal.getId(),
                        "90초 미체결 매수 취소 요청 "
                                + proposal.getSymbol()
                                + " (주문번호 "
                                + proposal.getBrokerOrderNo()
                                + ")");
            }
        }
    }

    private void syncHoldings(JsonNode balance) {
        List<Holding> brokerHoldings = trade.holdings(balance);
        Set<String> present = new HashSet<>();
        for (Holding source : brokerHoldings) {
            String key = source.exchange() + ":" + source.symbol();
            present.add(key);
            KiwoomUsAccountHolding entity =
                    holdingRepository
                            .findByExchangeAndSymbol(source.exchange(), source.symbol())
                            .orElseGet(KiwoomUsAccountHolding::new);
            boolean wasActive = entity.isActive();
            entity.setExchange(source.exchange());
            entity.setSymbol(source.symbol());
            entity.sync(
                    source.name(),
                    source.quantity(),
                    source.sellableQuantity(),
                    source.averagePrice(),
                    source.currentPrice(),
                    source.profitLossPercent());
            if (!wasActive
                    && proposalRepository.existsBySymbolAndActionAndStatusInAndOrderedAtAfter(
                            source.symbol(),
                            KiwoomUsTradeProposal.Action.BUY,
                            Set.of(
                                    KiwoomUsTradeProposal.Status.PARTIALLY_FILLED,
                                    KiwoomUsTradeProposal.Status.FILLED),
                            LocalDateTime.now().minusDays(1))) {
                entity.markManagedByAutoTrade();
            }
            holdingRepository.save(entity);
        }
        for (KiwoomUsAccountHolding entity : holdingRepository.findByActiveTrueOrderByIdAsc()) {
            if (!present.contains(entity.getExchange() + ":" + entity.getSymbol())) {
                entity.deactivate();
                holdingRepository.save(entity);
            }
        }
    }

    private boolean hasOpenSell(String symbol) {
        return proposalRepository.findByStatusIn(OPEN_STATUSES).stream()
                .anyMatch(
                        p ->
                                p.getAction() == KiwoomUsTradeProposal.Action.SELL
                                        && p.getSymbol().equals(symbol));
    }

    private long dailyBuys() {
        LocalDateTime start = KiwoomUsMarketHours.today().atStartOfDay();
        long total = 0;
        for (KiwoomUsTradeProposal.Status status :
                List.of(
                        KiwoomUsTradeProposal.Status.ORDERED,
                        KiwoomUsTradeProposal.Status.PARTIALLY_FILLED,
                        KiwoomUsTradeProposal.Status.FILLED,
                        KiwoomUsTradeProposal.Status.UNKNOWN)) {
            total +=
                    proposalRepository.countByActionAndStatusAndOrderedAtAfter(
                            KiwoomUsTradeProposal.Action.BUY, status, start);
        }
        return total;
    }

    private DecisionResult finishRun(
            KiwoomUsStrategyRun run, String status, String message, int count, Long proposalId) {
        run.setStatus(status);
        run.setMessage(message);
        runRepository.save(run);
        return new DecisionResult(status, message, count, proposalId);
    }

    private void log(String type, Long proposalId, String message) {
        audit.log(type, proposalId, message);
        events.publish(type, message, proposalId);
    }

    private void collectObjects(JsonNode node, List<JsonNode> result) {
        if (node == null) return;
        if (node.isObject()) {
            if (!text(node, "ord_no", "order_no").isBlank()) result.add(node);
            node.forEach(child -> collectObjects(child, result));
        } else if (node.isArray()) node.forEach(child -> collectObjects(child, result));
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
        String value = text(node, fields).replace(",", "").replace("%", "");
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

    private String safe(Throwable error) {
        String message =
                error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return message.substring(0, Math.min(900, message.length()));
    }

    private String format(double value) {
        return String.format("%.2f", value);
    }

    public record Candidate(
            int rank,
            String exchange,
            String symbol,
            String name,
            BigDecimal price,
            double changePercent,
            double volumeRatio,
            BigDecimal tradedValue) {}

    public record AccountSnapshot(
            UsdCash cash, BigDecimal investedUsd, BigDecimal totalAssetUsd, int positionCount) {}

    public record DecisionResult(
            String status, String message, int candidateCount, Long proposalId) {}
}
