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
import com.hyunchang.webapp.service.KiwoomUsTradeService.KrwOrderServiceStatus;
import com.hyunchang.webapp.service.KiwoomUsTradeService.OrderBookQuote;
import com.hyunchang.webapp.service.KiwoomUsTradeService.RankedStock;
import com.hyunchang.webapp.service.KiwoomUsTradeService.UsdCash;
import com.hyunchang.webapp.service.kiwoom.KiwoomUsAutoTradeState;
import com.hyunchang.webapp.util.KiwoomUsMarketHours;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class KiwoomUsAutoTradeService {
    private static final Duration API_TIMEOUT = Duration.ofSeconds(15);
    private static final int CANCEL_DISAPPEARANCE_CONFIRMATIONS = 2;
    private static final Set<KiwoomUsTradeProposal.Status> OPEN_STATUSES =
            Set.of(
                    KiwoomUsTradeProposal.Status.ORDERED,
                    KiwoomUsTradeProposal.Status.PARTIALLY_FILLED,
                    KiwoomUsTradeProposal.Status.CANCEL_REQUESTED,
                    KiwoomUsTradeProposal.Status.UNKNOWN);

    private final KiwoomProperties properties;
    private final KiwoomUsTradeService trade;
    private final KiwoomUsStrategySettingsService settingsService;
    private final KiwoomUsFundamentalService fundamentals;
    private final KiwoomUsIndexUniverseService indexUniverse;
    private final KiwoomUsAutoTradeState state;
    private final KiwoomUsAccountHoldingRepository holdingRepository;
    private final KiwoomUsTradeProposalRepository proposalRepository;
    private final KiwoomUsStrategyRunRepository runRepository;
    private final KiwoomUsAuditService audit;
    private final KiwoomUsEventService events;
    private final AtomicReference<List<Candidate>> lastCandidates =
            new AtomicReference<>(List.of());
    private final AtomicReference<AccountSnapshot> lastAccountSnapshot = new AtomicReference<>();
    private final Map<String, Integer> cancelDisappearanceCounts = new ConcurrentHashMap<>();

    public KiwoomUsAutoTradeService(
            KiwoomProperties properties,
            KiwoomUsTradeService trade,
            KiwoomUsStrategySettingsService settingsService,
            KiwoomUsFundamentalService fundamentals,
            KiwoomUsIndexUniverseService indexUniverse,
            KiwoomUsAutoTradeState state,
            KiwoomUsAccountHoldingRepository holdingRepository,
            KiwoomUsTradeProposalRepository proposalRepository,
            KiwoomUsStrategyRunRepository runRepository,
            KiwoomUsAuditService audit,
            KiwoomUsEventService events) {
        this.properties = properties;
        this.trade = trade;
        this.settingsService = settingsService;
        this.fundamentals = fundamentals;
        this.indexUniverse = indexUniverse;
        this.state = state;
        this.holdingRepository = holdingRepository;
        this.proposalRepository = proposalRepository;
        this.runRepository = runRepository;
        this.audit = audit;
        this.events = events;
    }

    @Scheduled(cron = "0 */10 * * * *")
    public void scheduledDecision() {
        if (properties.getUs().isStrategyEnabled()
                && state.isAutoTrading()
                && KiwoomUsMarketHours.isEntryWindow()) decide("SCHEDULE", true);
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 20_000)
    public void scheduledAccountAndExitSync() {
        if (!properties.isConfigured() || !KiwoomUsMarketHours.isOpen()) return;
        try {
            reconcileOrders();
            syncHoldings();
            evaluateExits();
        } catch (RuntimeException error) {
            log("ERROR", null, "계좌/주문 동기화 실패: " + safe(error));
        }
    }

    public DecisionResult decide(String triggeredBy, boolean allowOrder) {
        if (!state.tryStartDecision())
            return new DecisionResult("BUSY", "이미 후보를 산출 중입니다.", 0, null);
        KiwoomUsStrategyRun run = new KiwoomUsStrategyRun();
        run.setTriggeredBy(triggeredBy);
        try {
            validateDecisionReady();
            KiwoomUsStrategySettings settings = settingsService.current();
            AccountSnapshot snapshot = refreshAccountSnapshot();
            if (state.checkDailyLoss(
                    snapshot.automatedCapitalUsd().doubleValue(),
                    settings.getDailyLossLimitPercent())) {
                throw new IllegalStateException("미국계좌 일일 손실 한도에 도달해 신규 매수를 중지했습니다.");
            }
            CandidateScreeningResult screening =
                    filterCandidates(
                            trade.getTradeValueTop().block(API_TIMEOUT), settings, snapshot);
            List<Candidate> candidates = screening.candidates();
            lastCandidates.set(candidates);
            log("SCREENING", null, screening.stats().auditMessage());
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
                                + "배, PER "
                                + formatNullable(candidate.forwardPe())
                                + ", ROE "
                                + formatNullable(candidate.roePercent())
                                + "%, 스프레드 "
                                + format(candidate.spreadPercent())
                                + "%, 점수 "
                                + format(candidate.score())
                                + ", 지수="
                                + candidate.indexMembership());
            }
            run.setCandidateSummary(
                    candidates.stream().limit(10).map(Candidate::symbol).toList().toString());
            if (candidates.isEmpty())
                return finishRun(run, "NO_CANDIDATE", "모든 조건을 통과한 후보가 없습니다.", 0, null);
            if (!allowOrder)
                return finishRun(
                        run, "PREVIEW", "후보만 기록했습니다(미리보기는 주문을 전송하지 않음).", candidates.size(), null);
            if (!state.isAutoTrading())
                return finishRun(run, "PAUSED", "후보만 기록했습니다(자동매매 꺼짐).", candidates.size(), null);

            KiwoomUsTradeProposal proposal = submitBuy(candidates.getFirst(), settings);
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
        List<KiwoomUsAccountHolding> active = holdingRepository.findByActiveTrueOrderByIdAsc();
        BigDecimal stockEvaluation = trade.totalEvaluation(balance);
        BigDecimal managedEvaluation =
                active.stream()
                        .filter(KiwoomUsAccountHolding::isManagedByAutoTrade)
                        .map(
                                holding ->
                                        holding.getCurrentPrice() == null
                                                ? BigDecimal.ZERO
                                                : holding.getCurrentPrice()
                                                        .multiply(
                                                                BigDecimal.valueOf(
                                                                        holding.getQuantity())))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        long managedPositions =
                active.stream().filter(KiwoomUsAccountHolding::isManagedByAutoTrade).count();
        BigDecimal automatedCapital = cash.availableUsd().add(managedEvaluation);
        KiwoomUsStrategySettings settings = settingsService.current();
        BigDecimal perOrderLimit = percentage(cash.availableUsd(), effectiveOrderPercent(settings));
        KrwOrderServiceStatus krwOrderServiceStatus;
        try {
            krwOrderServiceStatus = trade.getKrwOrderServiceStatus().block(API_TIMEOUT);
        } catch (RuntimeException error) {
            krwOrderServiceStatus =
                    new KrwOrderServiceStatus(
                            "UNKNOWN", "확인 불가", "원화주문 서비스 상태 조회 실패: " + safe(error));
        }
        AccountSnapshot snapshot =
                new AccountSnapshot(
                        cash,
                        stockEvaluation,
                        managedEvaluation,
                        cash.availableUsd().add(stockEvaluation),
                        automatedCapital,
                        perOrderLimit,
                        active.size(),
                        (int) managedPositions,
                        krwOrderServiceStatus,
                        true,
                        "",
                        LocalDateTime.now());
        lastAccountSnapshot.set(snapshot);
        return snapshot;
    }

    /** 화면 조회용. 키움 일일 결제 처리 중에는 주문 판단과 분리해 마지막 정상값만 표시한다. */
    public AccountSnapshot accountSummary() {
        try {
            return refreshAccountSnapshot();
        } catch (RuntimeException error) {
            if (!KiwoomUsTradeService.isTemporaryAccountSettlementError(error)) throw error;
            AccountSnapshot cached = lastAccountSnapshot.get();
            String notice = "키움 수도결제 처리 중이라 마지막 정상 계좌 정보를 표시합니다. 잠시 후 자동 갱신됩니다.";
            if (cached == null) {
                return new AccountSnapshot(
                        new UsdCash(
                                BigDecimal.ZERO,
                                KiwoomUsTradeService.USD_CASH_SOURCE,
                                BigDecimal.ZERO,
                                true,
                                ""),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        0,
                        0,
                        new KrwOrderServiceStatus("UNKNOWN", "확인 대기", notice),
                        false,
                        notice,
                        null);
            }
            return new AccountSnapshot(
                    cached.cash(),
                    cached.stockEvaluationUsd(),
                    cached.managedEvaluationUsd(),
                    cached.totalAssetUsd(),
                    cached.automatedCapitalUsd(),
                    cached.perOrderLimitUsd(),
                    cached.positionCount(),
                    cached.managedPositionCount(),
                    cached.krwOrderServiceStatus(),
                    false,
                    notice,
                    cached.capturedAt());
        }
    }

    public UsdCash refreshUsdCash() {
        return trade.usdCash(trade.getDepositDetail().block(API_TIMEOUT));
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

    public List<KiwoomUsStrategyRun> runs() {
        return runRepository.findTop30ByOrderByIdDesc();
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

    CandidateScreeningResult filterCandidates(
            List<RankedStock> ranked, KiwoomUsStrategySettings settings, AccountSnapshot account) {
        return filterCandidates(
                ranked, settings, account, KiwoomUsMarketHours.regularSessionProgress());
    }

    CandidateScreeningResult filterCandidates(
            List<RankedStock> ranked,
            KiwoomUsStrategySettings settings,
            AccountSnapshot account,
            double regularSessionProgress) {
        List<RankedStock> source = ranked == null ? List.of() : ranked;
        Set<String> held = new HashSet<>();
        for (KiwoomUsAccountHolding holding : holdings()) held.add(holding.getSymbol());
        LocalDateTime cooldown = LocalDateTime.now().minusDays(settings.getSymbolCooldownDays());
        BigDecimal cashLimit = account.perOrderLimitUsd();
        boolean riskCapacity = account.managedPositionCount() < settings.getMaxPositions();

        List<RankedStock> liquid = new ArrayList<>();
        for (RankedStock stock : source) {
            if (stock.rank() <= 0 || stock.rank() > 50) {
                reject(stock, "순위·거래대금", "거래대금 순위=" + stock.rank() + ", 허용 순위=1~50");
            } else if (stock.tradedValue().signum() <= 0) {
                reject(stock, "순위·거래대금", "거래대금이 0 이하입니다.");
            } else {
                liquid.add(stock);
            }
        }

        List<RankedStock> indexed = new ArrayList<>();
        for (RankedStock stock : liquid) {
            if (indexUniverse.isEligible(stock.symbol())) indexed.add(stock);
            else reject(stock, "주요지수", "S&P 500 또는 NASDAQ-100 편입 종목이 아닙니다.");
        }

        List<RankedStock> momentum = new ArrayList<>();
        for (RankedStock stock : indexed) {
            if (stock.changePercent() >= settings.getMinChangePercent()
                    && stock.changePercent() <= settings.getMaxChangePercent()) {
                momentum.add(stock);
            } else {
                reject(
                        stock,
                        "등락률",
                        "현재="
                                + format(stock.changePercent())
                                + "%, 허용="
                                + format(settings.getMinChangePercent())
                                + "~"
                                + format(settings.getMaxChangePercent())
                                + "%");
            }
        }

        List<VolumeQualified> volume = new ArrayList<>();
        for (RankedStock stock : momentum) {
            double relativeVolume = stock.relativeVolumeRatio(regularSessionProgress);
            if (relativeVolume >= settings.getMinVolumeRatio()) {
                volume.add(new VolumeQualified(stock, relativeVolume));
            } else {
                reject(
                        stock,
                        "시간보정 RVOL",
                        "현재="
                                + format(relativeVolume)
                                + "배, 최소="
                                + format(settings.getMinVolumeRatio())
                                + "배");
            }
        }

        List<FundamentalQualified> quality = new ArrayList<>();
        for (VolumeQualified item : volume) {
            if (!settings.isFundamentalFilterEnabled()) {
                quality.add(new FundamentalQualified(item, null));
                continue;
            }
            Optional<KiwoomUsFundamentalService.FundamentalSnapshot> snapshot =
                    fundamentals.find(item.stock().symbol());
            if (snapshot.isEmpty()) {
                dataMissing(item.stock(), "PER·ROE", "유효한 PER 또는 ROE 데이터가 없습니다.");
                continue;
            }
            var value = snapshot.get();
            if (value.effectivePe() <= settings.getMaxForwardPe()
                    && value.roePercent() >= settings.getMinRoePercent()) {
                quality.add(new FundamentalQualified(item, value));
            } else {
                reject(
                        item.stock(),
                        "PER·ROE",
                        "PER="
                                + format(value.effectivePe())
                                + "(최대 "
                                + format(settings.getMaxForwardPe())
                                + "), ROE="
                                + format(value.roePercent())
                                + "%(최소 "
                                + format(settings.getMinRoePercent())
                                + "%)");
            }
        }

        List<OrderBookCheck> orderBookChecks =
                Optional.ofNullable(
                                Flux.fromIterable(quality)
                                        .flatMap(
                                                item -> {
                                                    RankedStock stock = item.volume().stock();
                                                    return trade.getOrderBook(
                                                                    stock.exchange(),
                                                                    stock.symbol())
                                                            .timeout(Duration.ofSeconds(4))
                                                            .map(
                                                                    quote ->
                                                                            new OrderBookCheck(
                                                                                    item, quote,
                                                                                    null))
                                                            .switchIfEmpty(
                                                                    Mono.just(
                                                                            new OrderBookCheck(
                                                                                    item,
                                                                                    null,
                                                                                    "조회 결과가 비어 있습니다.")))
                                                            .onErrorResume(
                                                                    error ->
                                                                            Mono.just(
                                                                                    new OrderBookCheck(
                                                                                            item,
                                                                                            null,
                                                                                            safe(
                                                                                                    error))));
                                                },
                                                4)
                                        .collectList()
                                        .block())
                        .orElseGet(List::of);

        List<SpreadQualified> spread = new ArrayList<>();
        for (OrderBookCheck check : orderBookChecks) {
            RankedStock stock = check.quality().volume().stock();
            if (check.quote() == null) {
                dataMissing(stock, "실시간 호가", check.errorMessage());
            } else if (check.quote().spreadPercent() > settings.getMaxSpreadPercent()) {
                reject(
                        stock,
                        "호가 스프레드",
                        "현재="
                                + format(check.quote().spreadPercent())
                                + "%, 최대="
                                + format(settings.getMaxSpreadPercent())
                                + "%");
            } else {
                spread.add(new SpreadQualified(check.quality(), check.quote()));
            }
        }

        List<SpreadQualified> affordable = new ArrayList<>();
        for (SpreadQualified item : spread) {
            RankedStock stock = item.quality().volume().stock();
            if (item.quote().ask().compareTo(cashLimit) <= 0) affordable.add(item);
            else
                reject(stock, "주문가능가격", "매도 1호가=$" + item.quote().ask() + ", 종목당 한도=$" + cashLimit);
        }

        List<SpreadQualified> notHeld = new ArrayList<>();
        for (SpreadQualified item : affordable) {
            RankedStock stock = item.quality().volume().stock();
            if (!held.contains(stock.symbol())) notHeld.add(item);
            else reject(stock, "미보유", "이미 보유 중인 종목입니다.");
        }

        List<SpreadQualified> cooldownPassed = new ArrayList<>();
        for (SpreadQualified item : notHeld) {
            RankedStock stock = item.quality().volume().stock();
            boolean recentlyBought =
                    proposalRepository.existsBySymbolAndActionAndOrderedAtAfter(
                            stock.symbol(), KiwoomUsTradeProposal.Action.BUY, cooldown);
            if (!recentlyBought) cooldownPassed.add(item);
            else
                reject(
                        stock,
                        "재매수 제한",
                        "최근 " + settings.getSymbolCooldownDays() + "일 이내 매수 이력이 있습니다.");
        }

        List<Candidate> result = new ArrayList<>();
        if (riskCapacity) {
            for (SpreadQualified item : cooldownPassed) {
                RankedStock stock = item.quality().volume().stock();
                var fundamental = item.quality().fundamental();
                result.add(
                        new Candidate(
                                stock.rank(),
                                stock.exchange(),
                                stock.symbol(),
                                stock.name(),
                                item.quote().ask(),
                                stock.changePercent(),
                                item.quality().volume().relativeVolume(),
                                fundamental == null ? null : fundamental.effectivePe(),
                                fundamental == null ? null : fundamental.roePercent(),
                                item.quote().spreadPercent(),
                                candidateScore(stock, item, settings),
                                stock.tradedValue(),
                                indexUniverse.membershipLabel(stock.symbol())));
            }
        } else {
            for (SpreadQualified item : cooldownPassed) {
                reject(
                        item.quality().volume().stock(),
                        "보유 한도",
                        "자동관리 보유="
                                + account.managedPositionCount()
                                + "개, 최대="
                                + settings.getMaxPositions()
                                + "개");
            }
        }
        result.sort(
                Comparator.comparingDouble(Candidate::score)
                        .reversed()
                        .thenComparingInt(Candidate::rank));

        CandidateScreeningStats stats =
                new CandidateScreeningStats(
                        source.size(),
                        liquid.size(),
                        indexed.size(),
                        momentum.size(),
                        volume.size(),
                        quality.size(),
                        spread.size(),
                        affordable.size(),
                        notHeld.size(),
                        cooldownPassed.size(),
                        result.size(),
                        cashLimit);
        return new CandidateScreeningResult(List.copyOf(result), stats);
    }

    private double candidateScore(
            RankedStock stock, SpreadQualified item, KiwoomUsStrategySettings settings) {
        double volumeScore =
                Math.min(3, item.quality().volume().relativeVolume() / settings.getMinVolumeRatio())
                        * 10;
        double liquidityScore = Math.max(0, 51 - stock.rank()) / 50.0 * 20;
        double spreadScore =
                Math.max(0, 1 - item.quote().spreadPercent() / settings.getMaxSpreadPercent()) * 15;
        var fundamental = item.quality().fundamental();
        double qualityScore = 0;
        if (fundamental != null) {
            qualityScore +=
                    Math.min(2, fundamental.roePercent() / Math.max(1, settings.getMinRoePercent()))
                            * 10;
            qualityScore +=
                    Math.min(2, settings.getMaxForwardPe() / fundamental.effectivePe()) * 7.5;
        }
        double midpoint = (settings.getMinChangePercent() + settings.getMaxChangePercent()) / 2;
        double halfRange =
                Math.max(
                        0.1, (settings.getMaxChangePercent() - settings.getMinChangePercent()) / 2);
        double momentumScore =
                Math.max(0, 1 - Math.abs(stock.changePercent() - midpoint) / halfRange) * 15;
        return Math.round(
                        (volumeScore + liquidityScore + spreadScore + qualityScore + momentumScore)
                                * 100.0)
                / 100.0;
    }

    private KiwoomUsTradeProposal submitBuy(
            Candidate candidate, KiwoomUsStrategySettings settings) {
        long dailyBuys = dailyBuys();
        if (dailyBuys >= settings.getDailyMaxBuys())
            throw new IllegalStateException("오늘 미국주식 매수 횟수 한도에 도달했습니다.");
        UsdCash latestCash = refreshUsdCash();
        if (!latestCash.usdOnlyBuyAllowed()) {
            state.emergencyStop(latestCash.blockReason());
            log("USD_CASH_BLOCK", null, latestCash.blockReason());
            throw new IllegalStateException(latestCash.blockReason());
        }
        BigDecimal unreservedUsd =
                latestCash.availableUsd().subtract(openBuyReserveUsd()).max(BigDecimal.ZERO);
        BigDecimal orderBudget = percentage(unreservedUsd, effectiveOrderPercent(settings));
        int quantity = orderBudget.divide(candidate.price(), 0, RoundingMode.DOWN).intValue();
        if (quantity < 1) throw new IllegalStateException("D+0 USD 외화예수금으로 1주를 살 수 없습니다.");

        KiwoomUsTradeProposal proposal = new KiwoomUsTradeProposal();
        proposal.setAction(KiwoomUsTradeProposal.Action.BUY);
        proposal.setExchange(candidate.exchange());
        proposal.setSymbol(candidate.symbol());
        proposal.setStockName(candidate.name());
        proposal.setQuantity(quantity);
        proposal.setLimitPrice(candidate.price());
        proposal.setReason("매수 조건 통과; 자금원=" + KiwoomUsTradeService.USD_CASH_SOURCE);
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
                log(
                        "ORDER_UNKNOWN",
                        proposal.getId(),
                        "매수 주문 결과 불확실 " + candidate.symbol() + ": 키움 주문번호 없음");
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
                if (KiwoomUsTradeService.isUsdOnlyFundingBlocked(error)) {
                    proposal.failed(safe(error));
                    proposalRepository.save(proposal);
                    state.emergencyStop(safe(error));
                    log("USD_CASH_BLOCK", proposal.getId(), "매수 차단: " + safe(error));
                } else if (KiwoomUsTradeService.isDefinitiveOrderFailure(error)) {
                    proposal.failed(safe(error));
                    proposalRepository.save(proposal);
                } else {
                    markAmbiguousOrder(proposal, error, "매수");
                }
            }
            throw error;
        }
    }

    private void evaluateExits() {
        KiwoomUsStrategySettings settings = settingsService.current();
        for (KiwoomUsAccountHolding holding : holdings()) {
            if (!holding.isManagedByAutoTrade() || holding.getSellableQuantity() <= 0) continue;
            double pnl = holding.getProfitLossPercent();
            Optional<KiwoomUsTradeProposal> openSell = findOpenSell(holding.getSymbol());
            if (openSell.isPresent()) {
                KiwoomUsTradeProposal proposal = openSell.get();
                if (pnl <= -settings.getStopLossPercent()
                        && proposal.getStatus() != KiwoomUsTradeProposal.Status.CANCEL_REQUESTED) {
                    requestOrderCancellation(proposal, "손절 전환을 위한 기존 매도 취소");
                }
                continue;
            }
            String reason = null;
            boolean market = false;
            int quantity = holding.getSellableQuantity();
            if (pnl <= -settings.getStopLossPercent()) {
                reason = "손절 " + format(pnl) + "%";
                market = true;
            } else if (pnl >= settings.getTakeProfitPercent2()) {
                reason = "2차 익절 " + format(pnl) + "%";
            } else if (pnl >= settings.getTakeProfitPercent()
                    && !holding.isFirstTakeProfitCompleted()) {
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
                log(
                        "ORDER_UNKNOWN",
                        proposal.getId(),
                        "매도 주문 결과 불확실 " + holding.getSymbol() + ": 키움 주문번호 없음");
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
            if (proposal.getStatus() == KiwoomUsTradeProposal.Status.PROPOSED) {
                if (KiwoomUsTradeService.isDefinitiveOrderFailure(error)) {
                    proposal.failed(safe(error));
                    proposalRepository.save(proposal);
                } else {
                    markAmbiguousOrder(proposal, error, "매도");
                }
            }
            log("ERROR", proposal.getId(), "매도 주문 실패 " + holding.getSymbol() + ": " + safe(error));
        }
    }

    private void markAmbiguousOrder(
            KiwoomUsTradeProposal proposal, RuntimeException error, String actionLabel) {
        proposal.unknown("주문 통신 결과 불확실: " + safe(error));
        proposalRepository.save(proposal);
        state.emergencyStop(actionLabel + " 주문 응답을 확인하지 못해 중복주문 방지를 위해 자동매매를 정지했습니다.");
        log(
                "ORDER_UNKNOWN",
                proposal.getId(),
                actionLabel + " 주문 결과 불확실 " + proposal.getSymbol() + ": 키움 주문·체결 내역을 확인하세요.");
    }

    public void reconcileOrders() {
        List<KiwoomUsTradeProposal> open = proposalRepository.findByStatusIn(OPEN_STATUSES);
        if (open.isEmpty()) return;
        JsonNode fills = trade.getTodayFills().block(API_TIMEOUT);
        JsonNode unfilled = trade.getOpenOrders().block(API_TIMEOUT);
        List<JsonNode> fillRecords = new ArrayList<>();
        List<JsonNode> openRecords = new ArrayList<>();
        collectObjects(fills, fillRecords);
        collectObjects(unfilled, openRecords);
        for (KiwoomUsTradeProposal proposal : open) {
            if (proposal.getBrokerOrderNo() == null || proposal.getBrokerOrderNo().isBlank())
                continue;
            List<JsonNode> matchedFills = matchingRecords(fillRecords, proposal);
            List<JsonNode> matchedOpenRecords = matchingRecords(openRecords, proposal);
            List<JsonNode> matchedRecords = new ArrayList<>(matchedFills);
            matchedRecords.addAll(matchedOpenRecords);

            applyBrokerFillState(proposal, matchedRecords);
            if (proposal.getStatus() == KiwoomUsTradeProposal.Status.FILLED) {
                cancelDisappearanceCounts.remove(proposal.getBrokerOrderNo());
                continue;
            }

            boolean explicitlyCanceled =
                    matchedRecords.stream()
                            .anyMatch(record -> isCancellationConfirmation(record, proposal));
            if (explicitlyCanceled) {
                confirmCancellation(proposal, "키움 주문 조회에서 취소 상태를 확인했습니다.");
                continue;
            }

            boolean matchedOpenOrder = !matchedOpenRecords.isEmpty();
            if (proposal.getStatus() == KiwoomUsTradeProposal.Status.CANCEL_REQUESTED) {
                if (matchedOpenOrder) {
                    cancelDisappearanceCounts.remove(proposal.getBrokerOrderNo());
                    if (proposal.getCancelRequestedAt() != null
                            && proposal.getCancelRequestedAt()
                                    .isBefore(LocalDateTime.now().minusSeconds(60))) {
                        requestOrderCancellation(proposal, "미완료 취소 재요청");
                    }
                } else if (cancelDisappearanceCounts.merge(
                                proposal.getBrokerOrderNo(), 1, Integer::sum)
                        >= CANCEL_DISAPPEARANCE_CONFIRMATIONS) {
                    confirmCancellation(proposal, "취소 요청 후 미체결 목록에서 2회 연속 사라진 취소 완료로 확정했습니다.");
                }
                continue;
            }
            if (matchedOpenOrder
                    && (proposal.getAction() == KiwoomUsTradeProposal.Action.BUY
                            || (proposal.getAction() == KiwoomUsTradeProposal.Action.SELL
                                    && proposal.getLimitPrice() != null))
                    && proposal.getOrderedAt() != null
                    && proposal.getOrderedAt().isBefore(LocalDateTime.now().minusSeconds(90))) {
                requestOrderCancellation(
                        proposal,
                        proposal.getAction() == KiwoomUsTradeProposal.Action.BUY
                                ? "90초 미체결 매수 취소"
                                : "90초 미체결 익절 매도 취소·재평가");
            }
        }
    }

    private void requestOrderCancellation(KiwoomUsTradeProposal proposal, String reason) {
        if (proposal.getBrokerOrderNo() == null
                || proposal.getBrokerOrderNo().isBlank()
                || proposal.getRemainingQuantity() <= 0) return;
        try {
            trade.cancelOrder(
                            proposal.getExchange(),
                            proposal.getSymbol(),
                            proposal.getBrokerOrderNo(),
                            proposal.getRemainingQuantity())
                    .block(API_TIMEOUT);
            proposal.requestCancel();
            proposalRepository.save(proposal);
            log(
                    proposal.getAction() == KiwoomUsTradeProposal.Action.BUY
                            ? "BUY_CANCEL"
                            : "SELL_CANCEL",
                    proposal.getId(),
                    reason
                            + " "
                            + proposal.getSymbol()
                            + " (주문번호 "
                            + proposal.getBrokerOrderNo()
                            + ")");
        } catch (RuntimeException error) {
            if (!KiwoomUsTradeService.isDefinitiveOrderFailure(error)) {
                proposal.requestCancel();
                proposalRepository.save(proposal);
            }
            log(
                    "ERROR",
                    proposal.getId(),
                    reason + " 요청 실패 " + proposal.getSymbol() + ": " + safe(error));
        }
    }

    private List<JsonNode> matchingRecords(List<JsonNode> records, KiwoomUsTradeProposal proposal) {
        return records.stream().filter(record -> matchesOrder(record, proposal)).toList();
    }

    private boolean matchesOrder(JsonNode record, KiwoomUsTradeProposal proposal) {
        String orderNo = proposal.getBrokerOrderNo();
        return orderNo.equals(text(record, "ord_no", "order_no"))
                || orderNo.equals(text(record, "orig_ord_no", "org_ord_no", "ori_ord_no"));
    }

    private void applyBrokerFillState(
            KiwoomUsTradeProposal proposal, List<JsonNode> matchedRecords) {
        int filled = proposal.getFilledQuantity();
        Integer remaining = null;
        BigDecimal averagePrice = null;
        boolean quantityReported = false;
        for (JsonNode record : matchedRecords) {
            Integer reportedFilled = nullableInteger(record, "cntr_qty", "filled_qty", "exec_qty");
            Integer reportedRemaining =
                    nullableInteger(
                            record,
                            "ord_remnq",
                            "rmn_qty",
                            "unfilled_qty",
                            "ord_remn_qty",
                            "oso_qty");
            Integer reportedOrdered = nullableInteger(record, "ord_qty", "order_qty", "qty");
            if (reportedFilled != null) {
                filled = Math.max(filled, reportedFilled);
                quantityReported = true;
            }
            if (reportedRemaining != null) {
                int ordered = reportedOrdered == null ? proposal.getQuantity() : reportedOrdered;
                filled = Math.max(filled, Math.max(0, ordered - reportedRemaining));
                remaining =
                        remaining == null
                                ? reportedRemaining
                                : Math.min(remaining, reportedRemaining);
                quantityReported = true;
            }
            BigDecimal reportedPrice =
                    decimal(record, "cntr_uv", "cntr_prc", "avg_cntr_pric", "exec_pric");
            if (reportedPrice.signum() > 0) averagePrice = reportedPrice;
        }
        if (!quantityReported) return;
        filled = Math.min(proposal.getQuantity(), filled);
        if (remaining == null) remaining = Math.max(0, proposal.getQuantity() - filled);

        KiwoomUsTradeProposal.Status before = proposal.getStatus();
        int beforeFilled = proposal.getFilledQuantity();
        proposal.syncFill(filled, remaining, averagePrice);
        proposalRepository.save(proposal);
        if (proposal.getAction() == KiwoomUsTradeProposal.Action.SELL
                && proposal.getReason() != null
                && proposal.getReason().startsWith("1차 익절")
                && beforeFilled == 0
                && proposal.getFilledQuantity() > 0) {
            holdingRepository
                    .findByExchangeAndSymbol(proposal.getExchange(), proposal.getSymbol())
                    .ifPresent(
                            holding -> {
                                holding.markFirstTakeProfitCompleted();
                                holdingRepository.save(holding);
                            });
        }
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

    private boolean isCancellationConfirmation(JsonNode record, KiwoomUsTradeProposal proposal) {
        if (!matchesOrder(record, proposal)) return false;
        for (String field :
                List.of(
                        "ord_stt",
                        "order_status",
                        "io_tp_nm",
                        "trde_tp",
                        "tsk_tp",
                        "mdfy_cncl_tp",
                        "mdfy_cncl",
                        "acpt_tp")) {
            String value = text(record, field).trim().toUpperCase();
            if (value.contains("취소")
                    || value.contains("CANCELLED")
                    || value.contains("CANCELED")
                    || value.equals("CANCEL")) return true;
        }
        return false;
    }

    private void confirmCancellation(KiwoomUsTradeProposal proposal, String evidence) {
        proposal.canceled();
        proposalRepository.save(proposal);
        cancelDisappearanceCounts.remove(proposal.getBrokerOrderNo());
        log(
                "ORDER_CANCELED",
                proposal.getId(),
                "주문 취소 확인 "
                        + proposal.getSymbol()
                        + " (주문번호 "
                        + proposal.getBrokerOrderNo()
                        + ", "
                        + evidence
                        + ")");
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
                                    KiwoomUsTradeProposal.Status.PARTIALLY_FILLED_CANCELED,
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

    private Optional<KiwoomUsTradeProposal> findOpenSell(String symbol) {
        return proposalRepository.findByStatusIn(OPEN_STATUSES).stream()
                .filter(
                        p ->
                                p.getAction() == KiwoomUsTradeProposal.Action.SELL
                                        && p.getSymbol().equals(symbol))
                .findFirst();
    }

    private long dailyBuys() {
        LocalDateTime start = KiwoomUsMarketHours.currentTradingDateStartInSystemZone();
        long total = 0;
        for (KiwoomUsTradeProposal.Status status :
                List.of(
                        KiwoomUsTradeProposal.Status.ORDERED,
                        KiwoomUsTradeProposal.Status.PARTIALLY_FILLED,
                        KiwoomUsTradeProposal.Status.PARTIALLY_FILLED_CANCELED,
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
        log(
                "DECISION_RESULT",
                proposalId,
                "[" + run.getTriggeredBy() + "][" + status + "] " + message + " 후보=" + count + "개");
        return new DecisionResult(status, message, count, proposalId);
    }

    private void log(String type, Long proposalId, String message) {
        audit.log(type, proposalId, message);
        events.publish(type, message, proposalId);
    }

    private void reject(RankedStock stock, String stage, String reason) {
        log(
                "CANDIDATE_REJECTED",
                null,
                "종목=" + stockLabel(stock) + ", 단계=" + stage + ", 사유=" + reason);
    }

    private void dataMissing(RankedStock stock, String dataType, String reason) {
        String detail = reason == null || reason.isBlank() ? "조회 결과가 비어 있습니다." : reason;
        log(
                "DATA_MISSING",
                null,
                "종목=" + stockLabel(stock) + ", 데이터=" + dataType + ", 사유=" + detail + " → 후보 제외");
    }

    private String stockLabel(RankedStock stock) {
        String name =
                stock.name() == null || stock.name().isBlank() ? "" : "(" + stock.name() + ")";
        return stock.symbol() + name;
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

    private Integer nullableInteger(JsonNode node, String... fields) {
        String value = text(node, fields).replace(",", "").trim();
        if (value.isBlank()) return null;
        try {
            return new BigDecimal(value).abs().intValue();
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String safe(Throwable error) {
        String message =
                error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return message.substring(0, Math.min(900, message.length()));
    }

    private String format(double value) {
        return String.format("%.2f", value);
    }

    private String formatNullable(Double value) {
        return value == null ? "미사용" : format(value);
    }

    private BigDecimal percentage(BigDecimal amount, double percent) {
        return amount.multiply(BigDecimal.valueOf(percent))
                .divide(BigDecimal.valueOf(100), 4, RoundingMode.DOWN)
                .max(BigDecimal.ZERO);
    }

    private double effectiveOrderPercent(KiwoomUsStrategySettings settings) {
        return Math.min(
                settings.getMaxOrderPercent(), KiwoomUsTradeService.USD_ONLY_MAX_SPEND_PERCENT);
    }

    private BigDecimal openBuyReserveUsd() {
        return proposalRepository.findByStatusIn(OPEN_STATUSES).stream()
                .filter(p -> p.getAction() == KiwoomUsTradeProposal.Action.BUY)
                .filter(p -> p.getLimitPrice() != null && p.getLimitPrice().signum() > 0)
                .map(
                        p ->
                                p.getLimitPrice()
                                        .multiply(
                                                BigDecimal.valueOf(
                                                        Math.max(0, p.getRemainingQuantity()))))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public record Candidate(
            int rank,
            String exchange,
            String symbol,
            String name,
            BigDecimal price,
            double changePercent,
            double volumeRatio,
            Double forwardPe,
            Double roePercent,
            double spreadPercent,
            double score,
            BigDecimal tradedValue,
            String indexMembership) {}

    private record VolumeQualified(RankedStock stock, double relativeVolume) {}

    private record FundamentalQualified(
            VolumeQualified volume, KiwoomUsFundamentalService.FundamentalSnapshot fundamental) {}

    private record SpreadQualified(FundamentalQualified quality, OrderBookQuote quote) {}

    private record OrderBookCheck(
            FundamentalQualified quality, OrderBookQuote quote, String errorMessage) {}

    record CandidateScreeningResult(List<Candidate> candidates, CandidateScreeningStats stats) {}

    record CandidateScreeningStats(
            int inputCount,
            int liquidCount,
            int indexCount,
            int momentumCount,
            int volumeCount,
            int fundamentalCount,
            int spreadCount,
            int affordableCount,
            int notHeldCount,
            int cooldownCount,
            int capacityCount,
            BigDecimal perOrderLimitUsd) {
        String auditMessage() {
            StringBuilder message = new StringBuilder("후보 필터 단계별 잔존/탈락: 원본=").append(inputCount);
            appendStage(message, "순위·거래대금", inputCount, liquidCount);
            appendStage(message, "주요지수", liquidCount, indexCount);
            appendStage(message, "등락률", indexCount, momentumCount);
            appendStage(message, "시간보정거래량", momentumCount, volumeCount);
            appendStage(message, "PER·ROE", volumeCount, fundamentalCount);
            appendStage(message, "스프레드", fundamentalCount, spreadCount);
            appendStage(message, "주문가능가격", spreadCount, affordableCount);
            message.append("(한도=$")
                    .append(perOrderLimitUsd.setScale(2, RoundingMode.DOWN))
                    .append(')');
            appendStage(message, "미보유", affordableCount, notHeldCount);
            appendStage(message, "재매수제한", notHeldCount, cooldownCount);
            appendStage(message, "보유한도", cooldownCount, capacityCount);
            message.append(" → 최종=").append(capacityCount);
            return message.toString();
        }

        private static void appendStage(
                StringBuilder message, String label, int previousCount, int remainingCount) {
            message.append(" → ")
                    .append(label)
                    .append('=')
                    .append(remainingCount)
                    .append("(탈락 ")
                    .append(Math.max(0, previousCount - remainingCount))
                    .append(')');
        }
    }

    public record AccountSnapshot(
            UsdCash cash,
            BigDecimal stockEvaluationUsd,
            BigDecimal managedEvaluationUsd,
            BigDecimal totalAssetUsd,
            BigDecimal automatedCapitalUsd,
            BigDecimal perOrderLimitUsd,
            int positionCount,
            int managedPositionCount,
            KrwOrderServiceStatus krwOrderServiceStatus,
            boolean fresh,
            String notice,
            LocalDateTime capturedAt) {}

    public record DecisionResult(
            String status, String message, int candidateCount, Long proposalId) {}
}
