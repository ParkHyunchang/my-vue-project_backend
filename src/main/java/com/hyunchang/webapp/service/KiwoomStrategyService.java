package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.entity.KiwoomStrategyRun;
import com.hyunchang.webapp.entity.KiwoomTradeProposal;
import com.hyunchang.webapp.repository.KiwoomStrategyRunRepository;
import com.hyunchang.webapp.repository.KiwoomTradeProposalRepository;
import com.hyunchang.webapp.service.ai.AiProviderChain;
import com.hyunchang.webapp.service.kiwoom.KiwoomAutoTradeState;
import com.hyunchang.webapp.service.kiwoom.KiwoomWebsocketClient;
import com.hyunchang.webapp.service.prompt.AiPromptCatalog;
import com.hyunchang.webapp.service.prompt.AiPromptService;
import com.hyunchang.webapp.util.KiwoomMarketHours;
import com.hyunchang.webapp.util.KiwoomPriceRules;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 키움 자동매매 전략 엔진 — 계좌 잔고·KRX 자동 스캔 스윙 후보·스윙지표를 모아 AI에 구조화 JSON 판단을 요청하고, 제안(proposal)으로 저장하는 데까지만
 * 담당한다. 사람이 등록하는 관심종목은 없다 — 매수 후보군은 전부 자동 스캔에서 나온다. 주문 전송은 KiwoomProposalOrderService가 별도의 승인·사전점검
 * 게이트를 거쳐 수행한다.
 */
@Service
public class KiwoomStrategyService {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Logger log = LoggerFactory.getLogger(KiwoomStrategyService.class);

    /** 압축 프롬프트에서 보유·매매후보·스윙 목록에 남기는 최대 줄 수 (Groq 8000자 한도 대응) */
    private static final int COMPACT_MAX_LINES = 10;

    /** 유니버스에 편입되는 스윙 후보 상한 — ShortSwingCandidateService.MAX_SCREENED_CANDIDATES와 동일한 관례. */
    private static final int SWING_CANDIDATE_LIMIT = 30;

    /** 실제 키움 주문이 접수됐거나 체결된 상태만 같은 종목 재주문 대기시간의 대상이다. */
    private static final List<KiwoomTradeProposal.Status> REORDER_COOLDOWN_STATUSES =
            List.of(
                    KiwoomTradeProposal.Status.ORDERED,
                    KiwoomTradeProposal.Status.PARTIALLY_FILLED,
                    KiwoomTradeProposal.Status.CANCEL_REQUESTED,
                    KiwoomTradeProposal.Status.FILLED,
                    KiwoomTradeProposal.Status.ORDER_UNKNOWN);

    private volatile String lastCandidateSignature;
    private volatile LocalDateTime lastCandidateDecisionAt;

    private final KiwoomProperties props;
    private final KiwoomTradeService trade;
    private final KiwoomAutoTradeState state;
    private final AiPromptService prompts;
    private final AiProviderChain ai;
    private final ObjectMapper json;
    private final KiwoomStrategyRunRepository runs;
    private final KiwoomTradeProposalRepository proposals;
    private final KiwoomWebsocketClient events;
    private final KiwoomProposalOrderService orders;
    private final ShortSwingCandidateService catalystService;
    private final KiwoomStrategySettingsService settings;
    private final KiwoomStrategyAuditService audit;
    private final KiwoomAccountHoldingSyncService accountHoldings;

    public KiwoomStrategyService(
            KiwoomProperties props,
            KiwoomTradeService trade,
            KiwoomAutoTradeState state,
            AiPromptService prompts,
            AiProviderChain ai,
            ObjectMapper json,
            KiwoomStrategyRunRepository runs,
            KiwoomTradeProposalRepository proposals,
            KiwoomWebsocketClient events,
            KiwoomProposalOrderService orders,
            ShortSwingCandidateService catalystService,
            KiwoomStrategySettingsService settings,
            KiwoomStrategyAuditService audit,
            KiwoomAccountHoldingSyncService accountHoldings) {
        this.props = props;
        this.trade = trade;
        this.state = state;
        this.prompts = prompts;
        this.ai = ai;
        this.json = json;
        this.runs = runs;
        this.proposals = proposals;
        this.events = events;
        this.orders = orders;
        this.catalystService = catalystService;
        this.settings = settings;
        this.audit = audit;
        this.accountHoldings = accountHoldings;
    }

    @Scheduled(cron = "0 0/15 9-15 * * MON-FRI", zone = "Asia/Seoul")
    public void scheduledDecision() {
        if (props.getStrategy().isEnabled()
                && state.isAutoTrading()
                && props.isConfigured()
                && KiwoomMarketHours.isOpen()) {
            try {
                runDecision("SCHEDULE");
            } catch (IllegalStateException ignored) {
                // 안전 자동중지 또는 이미 실행 중 — 다음 주기에 다시 시도한다.
            }
        }
    }

    public DecisionResult runDecision(String by) {
        if (state.isEmergencyStopped())
            throw new IllegalStateException("안전 자동중지 상태입니다. 자동주문 시작 버튼으로 다시 시작하세요.");
        if (!state.tryStartDecision()) throw new IllegalStateException("이미 전략 판단이 실행 중입니다.");
        KiwoomStrategyRun run = new KiwoomStrategyRun();
        run.setTriggeredBy(
                "SCHEDULE".equals(by)
                        ? KiwoomStrategyRun.TriggeredBy.SCHEDULE
                        : KiwoomStrategyRun.TriggeredBy.MANUAL);
        try {
            events.publishEvent("strategy", "AI 전략 판단을 시작합니다.");
            JsonNode depositNode = trade.getDeposit().block(Duration.ofSeconds(10));
            JsonNode balance = trade.getBalance().block(Duration.ofSeconds(10));
            // 이번 판단이 실제로 사용할 실계좌 잔고를 화면·이력용 DB 스냅샷에도 남긴다.
            // 별도 호출 없이 같은 응답을 재사용하므로 API 비용은 늘지 않는다.
            accountHoldings.syncBalance(balance, "STRATEGY_" + by);
            // 일일 손실 한도 체크 — 이미 조회한 예수금·잔고를 재사용하므로 추가 API 호출이 없다.
            KiwoomTradeService.AccountAsset accountAsset = trade.accountAsset(depositNode, balance);
            long totalAsset = accountAsset.amount();
            // 주문 가능 금액은 주문 수량·예수금 안전검사에만 사용한다. 총자산 계산에는 사용하지 않는다.
            long deposit = number(depositNode, "ord_alow_amt", "entr");
            double dailyLossLimit = settings.current().getDailyLossLimitPercent();
            long netCashFlow =
                    dailyLossLimit > 0
                            ? trade.getTodayCashFlow().block(Duration.ofSeconds(10)).netAmount()
                            : 0;
            if (state.recordDailyLossCheck(totalAsset, netCashFlow, dailyLossLimit)) {
                KiwoomAutoTradeState.DailyLossStatus loss = state.dailyLossStatus();
                String detail = dailyLossTriggerDetail(loss, dailyLossLimit, accountAsset.source());
                log.warn("[자동매매][일일 손실 한도 발동] {}", detail);
                audit.log("DAILY_LOSS_TRIGGERED", null, detail);
                events.publishEvent("strategy", "일일 손실 한도 발동 — " + detail);
            }

            // 유니버스 = 실계좌 보유 종목 ∪ KRX 자동 스캔 스윙 후보(최대 SWING_CANDIDATE_LIMIT개).
            // 사람이 등록하는 관심종목은 없다 — 보유 종목이 있어야 SELL 판단이 가능하다.
            List<KiwoomTradeService.Holding> holdings = trade.parseHoldings(balance);
            Map<String, String> universe = new LinkedHashMap<>();
            Map<String, Integer> sellableQty = new HashMap<>();
            Map<String, Long> currentPrices = new HashMap<>();
            List<String> holdingLines = new ArrayList<>();
            for (KiwoomTradeService.Holding h : holdings) {
                universe.put(h.code(), h.name());
                sellableQty.put(h.code(), h.sellable());
                currentPrices.put(h.code(), h.curPrice());
                holdingLines.add(promptLine(h));
            }
            // 스윙 후보는 이번 판단 안에서 한 번만 조회해 유니버스 구성·신호 텍스트·BUY 검증에 함께 재사용한다.
            // DART 공시·뉴스 촉매까지 확인된 후보만 남기므로(ShortSwingCandidateService), 숫자만으로
            // 급등을 판단하지 않고 실제 근거가 있는지까지 반영한다.
            List<ShortSwingCandidateService.KrCandidateCatalyst> catalystCandidates =
                    fetchCatalystCandidates();
            Map<String, KrxOpenApiService.KrSwingCandidate> swingCandidates =
                    indexByCode(
                            catalystCandidates.stream()
                                    .map(ShortSwingCandidateService.KrCandidateCatalyst::candidate)
                                    .toList());
            Map<String, ShortSwingCandidateService.CatalystStatus> catalystStatuses =
                    new HashMap<>();
            List<String> candidateLines = new ArrayList<>();
            for (ShortSwingCandidateService.KrCandidateCatalyst cc : catalystCandidates) {
                KrxOpenApiService.KrSwingCandidate c = cc.candidate();
                catalystStatuses.put(c.bareCode(), cc.status());
                universe.putIfAbsent(c.bareCode(), c.name());
                if (!sellableQty.containsKey(c.bareCode())) {
                    candidateLines.add(candidateLine(c, describeCatalyst(cc)));
                }
            }
            String swing = swingSignals(swingCandidates, universe.keySet());
            String guardRules = guardRules(deposit);
            String candidateSignature = candidateSignature(catalystCandidates);
            log.info(
                    "[자동매매][후보 검토] 실행={}, 조건=[{}], 보유={}종목, 매수 후보={}종목, 후보 목록={}",
                    "SCHEDULE".equals(by) ? "자동" : "수동",
                    reviewConditions(),
                    holdings.size(),
                    candidateLines.size(),
                    candidateSummary(catalystCandidates));
            if ("SCHEDULE".equals(by)
                    && candidateSignature.equals(lastCandidateSignature)
                    && !holdingExitTriggered(holdings)
                    && !candidateReevaluationDue()) {
                return skipped(run, "후보 변동이 없고 보유 종목의 손절·익절 신호도 없어 AI 검토를 건너뜁니다.");
            }

            String prompt =
                    render(
                            deposit,
                            holdingLines,
                            candidateLines,
                            swing,
                            guardRules,
                            Integer.MAX_VALUE);
            String compactPrompt =
                    render(
                            deposit,
                            holdingLines,
                            candidateLines,
                            swing,
                            guardRules,
                            COMPACT_MAX_LINES);
            run.setPromptChars(prompt.length());
            run.setInputTokens(estimateTokens(prompt));
            run.setAiCalled(true);

            AiProviderChain.ChainResult result = ai.analyze(prompt, compactPrompt, true);
            if (!result.success()) {
                run.setStatus(KiwoomStrategyRun.Status.BLOCKED);
                run.setErrorMessage(
                        "AI 제공자가 모두 차단/실패했습니다."
                                + (result.retryAt() == null ? "" : " 재시도 가능: " + result.retryAt()));
                runs.save(run);
                return new DecisionResult(run.getId(), run.getStatus().name(), 0);
            }
            run.setProviderName(result.providerName());
            run.setModel(result.model());
            run.setOutputTokens(estimateTokens(result.text()));
            JsonNode root = parse(result.text());
            if (root == null || !root.has("decisions")) {
                run.setStatus(KiwoomStrategyRun.Status.PARSE_FAILED);
                run.setErrorMessage("AI 응답 JSON 파싱에 실패했습니다.");
                runs.save(run);
                return new DecisionResult(run.getId(), run.getStatus().name(), 0);
            }
            run.setMarketView(root.path("marketView").asText(""));
            run.setStatus(KiwoomStrategyRun.Status.SUCCESS);
            runs.save(run);
            int saved = 0;
            int buyCount = 0;
            int sellCount = 0;
            List<KiwoomTradeProposal> holdProposals = new ArrayList<>();
            Set<String> respondedCodes = new HashSet<>();
            Set<String> acceptedCodes = new HashSet<>();
            Map<String, List<ValidationFailure>> rejectedByCode = new LinkedHashMap<>();
            Map<ValidationFailure, Integer> rejectionCounts =
                    new EnumMap<>(ValidationFailure.class);
            int responseDecisionCount =
                    root.path("decisions").isArray() ? root.path("decisions").size() : 0;
            int rejectedResponseCount = 0;
            for (JsonNode d : root.path("decisions")) {
                DecisionValidation validation =
                        validated(
                                d,
                                universe,
                                currentPrices,
                                swingCandidates,
                                catalystStatuses,
                                deposit);
                String code = validation.stockCode();
                if (code != null && universe.containsKey(code)) respondedCodes.add(code);
                if (!validation.accepted()) {
                    rejectedResponseCount++;
                    rejectionCounts.merge(validation.failure(), 1, Integer::sum);
                    if (code != null && universe.containsKey(code)) {
                        rejectedByCode
                                .computeIfAbsent(code, ignored -> new ArrayList<>())
                                .add(validation.failure());
                    } else {
                        audit.log(
                                "AI_DECISION_REJECTED",
                                null,
                                "AI 판단을 서버 검증에서 제외했습니다: "
                                        + validation.failure().description()
                                        + (code == null ? "" : " (" + code + ")"));
                    }
                    continue;
                }
                if (!acceptedCodes.add(code)) {
                    rejectedResponseCount++;
                    rejectionCounts.merge(ValidationFailure.DUPLICATE_DECISION, 1, Integer::sum);
                    audit.log("AI_DECISION_REJECTED", null, "동일 종목의 중복 AI 판단을 제외했습니다: " + code);
                    continue;
                }
                KiwoomTradeProposal p = validation.proposal();
                applyGuardFlags(p, deposit);
                p.setRun(run);
                proposals.save(p);
                audit.log(
                        "CANDIDATE_SELECTED",
                        p.getId(),
                        p.getAction() + " " + p.getStockCode() + " — " + p.getReason());
                saved++;
                if (p.getAction() == KiwoomTradeProposal.Action.HOLD) {
                    holdProposals.add(p);
                    continue;
                }
                if (p.getAction() == KiwoomTradeProposal.Action.BUY) buyCount++;
                else sellCount++;
                log.info(
                        "[자동매매][AI 판단] {} {}({}), {}주, 신뢰도={}%, 사유={}",
                        actionLabel(p.getAction()),
                        p.getStockName(),
                        p.getStockCode(),
                        p.getQuantity(),
                        p.getConfidence(),
                        trim(p.getReason()));
                // 자동 주문 전송이 켜져 있으면 실행 주체와 무관하게 동일한 안전 검사를 거쳐 전송한다.
                // 화면의 수동 판단 기능을 없앤 뒤에도, 기존에 직접 실행된 판단이
                // PROPOSED 상태로 멈추지 않도록 자동 경로를 일관되게 적용한다.
                autoSubmit(p);
            }
            List<String> omittedCodes = new ArrayList<>();
            List<String> rejectedCodes = new ArrayList<>();
            for (Map.Entry<String, String> entry : universe.entrySet()) {
                if (acceptedCodes.contains(entry.getKey())) continue;
                List<ValidationFailure> failures = rejectedByCode.get(entry.getKey());
                boolean rejected = respondedCodes.contains(entry.getKey());
                KiwoomTradeProposal p =
                        rejected
                                ? rejectedDecisionHold(
                                        entry.getKey(), entry.getValue(), run, failures)
                                : omittedDecisionHold(entry.getKey(), entry.getValue(), run);
                proposals.save(p);
                if (rejected) {
                    String detail = validationFailureSummary(failures);
                    audit.log(
                            "AI_DECISION_REJECTED",
                            p.getId(),
                            "AI 응답을 서버 안전 검증에서 탈락시켜 관망으로 보정했습니다: "
                                    + entry.getKey()
                                    + " — "
                                    + detail);
                    if (failures != null && failures.contains(ValidationFailure.CATALYST_NOT_FOUND))
                        audit.log(
                                "BUY_BLOCKED_CATALYST_MISSING",
                                p.getId(),
                                entry.getKey() + " 자동매수 차단: 확인된 촉매 없음");
                    if (failures != null
                            && failures.contains(ValidationFailure.CATALYST_UNAVAILABLE))
                        audit.log(
                                "CATALYST_LOOKUP_UNAVAILABLE",
                                p.getId(),
                                entry.getKey() + " 자동매수 차단: 촉매 조회 불가");
                    rejectedCodes.add(entry.getKey() + "(" + detail + ")");
                } else {
                    audit.log(
                            "AI_DECISION_OMITTED",
                            p.getId(),
                            "AI 응답에서 판단이 누락되어 서버가 안전 관망으로 보정했습니다: " + entry.getKey());
                    omittedCodes.add(entry.getKey());
                }
                holdProposals.add(p);
                saved++;
            }
            if (!rejectedCodes.isEmpty()) {
                log.warn(
                        "[자동매매][AI 검증 탈락 보정] 탈락={}종목, 처리=안전 관망, 목록={}",
                        rejectedCodes.size(),
                        String.join(", ", rejectedCodes));
                events.publishEvent(
                        "strategy",
                        "AI 판단 중 서버 검증에서 탈락한 " + rejectedCodes.size() + "종목을 안전 관망으로 보정했습니다.");
            }
            if (!omittedCodes.isEmpty()) {
                log.warn(
                        "[자동매매][AI 판단 누락 보정] 누락={}종목, 처리=안전 관망, 목록={}",
                        omittedCodes.size(),
                        String.join(", ", omittedCodes));
                events.publishEvent(
                        "strategy", "AI가 판단을 누락한 " + omittedCodes.size() + "종목을 안전 관망으로 보정했습니다.");
            }
            if (!holdProposals.isEmpty())
                log.info(
                        "[자동매매][AI 관망 요약] {}종목, 목록={}",
                        holdProposals.size(),
                        holdSummary(holdProposals));
            state.markRun();
            lastCandidateSignature = candidateSignature;
            lastCandidateDecisionAt = LocalDateTime.now(KST);
            events.publishEvent("strategy", "AI 전략 제안 " + saved + "건을 생성했습니다.");
            log.info(
                    "[자동매매][후보 검토 완료] 유니버스={}종목, AI 응답={}건, 저장={}건 (매수={}건, 매도={}건, 관망={}건), 검증 탈락 응답={}건, 검증 탈락 종목={}종목, 응답 누락={}종목, 탈락 사유=[{}], 매수 후보={}종목",
                    universe.size(),
                    responseDecisionCount,
                    saved,
                    buyCount,
                    sellCount,
                    holdProposals.size(),
                    rejectedResponseCount,
                    rejectedCodes.size(),
                    omittedCodes.size(),
                    rejectionSummary(rejectionCounts),
                    candidateLines.size());
            return new DecisionResult(run.getId(), run.getStatus().name(), saved);
        } catch (Exception e) {
            run.setStatus(KiwoomStrategyRun.Status.FAILED);
            run.setErrorMessage(trim(e.getMessage()));
            runs.save(run);
            events.publishEvent("error", "전략 판단 실패: " + trim(e.getMessage()));
            return new DecisionResult(run.getId(), run.getStatus().name(), 0);
        } finally {
            state.finishDecision();
        }
    }

    private boolean holdingExitTriggered(List<KiwoomTradeService.Holding> holdings) {
        var s = settings.current();
        return holdings.stream()
                .anyMatch(
                        h ->
                                h.plPct() <= -s.getSwingStopLossPercent()
                                        || h.plPct() >= s.getSwingTakeProfitPercent());
    }

    private boolean candidateReevaluationDue() {
        if (lastCandidateDecisionAt == null) return true;
        return !lastCandidateDecisionAt
                .plusMinutes(settings.current().getCandidateReevaluationMinutes())
                .isAfter(LocalDateTime.now(KST));
    }

    private String candidateSignature(
            List<ShortSwingCandidateService.KrCandidateCatalyst> candidates) {
        return candidates.stream()
                .map(
                        cc -> {
                            KrxOpenApiService.KrSwingCandidate c = cc.candidate();
                            return c.bareCode()
                                    + ':'
                                    + Math.round(c.changePercent() * 10)
                                    + ':'
                                    + Math.round(c.volumeRatio() * 10)
                                    + ':'
                                    + describeCatalyst(cc);
                        })
                .collect(java.util.stream.Collectors.joining("|"));
    }

    private DecisionResult skipped(KiwoomStrategyRun run, String reason) {
        run.setStatus(KiwoomStrategyRun.Status.SKIPPED);
        run.setErrorMessage(reason);
        runs.save(run);
        events.publishEvent("strategy", reason);
        log.info("[자동매매][후보 검토 건너뜀] 조건=[{}], 사유={}", reviewConditions(), reason);
        return new DecisionResult(run.getId(), run.getStatus().name(), 0);
    }

    private String reviewConditions() {
        var s = settings.current();
        return "상승률 +"
                + s.getSwingMinChangePercent()
                + "%~+"
                + s.getSwingMaxChangePercent()
                + "%, 거래량 "
                + s.getSwingMinVolumeRatio()
                + "배 이상, 재검토 "
                + s.getCandidateReevaluationMinutes()
                + "분, 자동 주문 신뢰도 "
                + s.getAutoExecuteMinConfidence()
                + "% 이상, 자동매수 촉매 "
                + (s.isRequireCatalystForAutoBuy() ? "필수" : "선택");
    }

    private String candidateSummary(
            List<ShortSwingCandidateService.KrCandidateCatalyst> candidates) {
        if (candidates.isEmpty()) return "없음";
        return candidates.stream()
                .limit(10)
                .map(
                        cc -> {
                            KrxOpenApiService.KrSwingCandidate c = cc.candidate();
                            return c.name()
                                    + "("
                                    + c.bareCode()
                                    + ", "
                                    + String.format("%+.1f%%", c.changePercent())
                                    + ", 거래량 "
                                    + String.format("%.1f배", c.volumeRatio())
                                    + ")";
                        })
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private String holdSummary(List<KiwoomTradeProposal> holds) {
        return holds.stream()
                .limit(12)
                .map(
                        p ->
                                p.getStockName()
                                        + "("
                                        + p.getStockCode()
                                        + ", "
                                        + p.getConfidence()
                                        + "%, "
                                        + holdReason(p.getReason())
                                        + ")")
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private String holdReason(String reason) {
        if (reason == null || reason.isBlank()) return "근거 부족";
        if (reason.contains("직접적인 연관성이 낮")) return "뉴스 관련성 낮음";
        if (reason.contains("촉매 미확인")) return "촉매 미확인";
        if (reason.contains("상승 모멘텀")) return "상승 모멘텀 약함";
        return trim(reason).substring(0, Math.min(60, trim(reason).length()));
    }

    private String actionLabel(KiwoomTradeProposal.Action action) {
        return switch (action) {
            case BUY -> "매수";
            case SELL -> "매도";
            case HOLD -> "관망";
        };
    }

    private KiwoomTradeProposal omittedDecisionHold(
            String stockCode, String stockName, KiwoomStrategyRun run) {
        KiwoomTradeProposal proposal = new KiwoomTradeProposal();
        proposal.setAction(KiwoomTradeProposal.Action.HOLD);
        proposal.setStockCode(stockCode);
        proposal.setStockName(stockName);
        proposal.setQuantity(0);
        proposal.setConfidence(0);
        proposal.setReason("AI 응답에서 해당 종목 판단이 누락되어 서버가 안전 관망 처리했습니다.");
        proposal.setOrderType(KiwoomTradeProposal.OrderType.LIMIT);
        proposal.setRun(run);
        return proposal;
    }

    private KiwoomTradeProposal rejectedDecisionHold(
            String stockCode,
            String stockName,
            KiwoomStrategyRun run,
            List<ValidationFailure> failures) {
        KiwoomTradeProposal proposal = new KiwoomTradeProposal();
        proposal.setAction(KiwoomTradeProposal.Action.HOLD);
        proposal.setStockCode(stockCode);
        proposal.setStockName(stockName);
        proposal.setQuantity(0);
        proposal.setConfidence(0);
        proposal.setReason(
                "AI 응답이 서버 안전 검증을 통과하지 못해 관망 처리했습니다. 사유=" + validationFailureSummary(failures));
        proposal.setOrderType(KiwoomTradeProposal.OrderType.LIMIT);
        proposal.setRun(run);
        return proposal;
    }

    private String validationFailureSummary(List<ValidationFailure> failures) {
        if (failures == null || failures.isEmpty())
            return ValidationFailure.MALFORMED.description();
        return failures.stream()
                .distinct()
                .map(ValidationFailure::description)
                .collect(java.util.stream.Collectors.joining(" / "));
    }

    private String rejectionSummary(Map<ValidationFailure, Integer> counts) {
        if (counts.isEmpty()) return "없음";
        return counts.entrySet().stream()
                .map(entry -> entry.getKey().description() + " " + entry.getValue() + "건")
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private int estimateTokens(String text) {
        return text == null || text.isBlank() ? 0 : Math.max(1, (text.length() + 3) / 4);
    }

    private void autoSubmit(KiwoomTradeProposal proposal) {
        if (proposal.getAction() == KiwoomTradeProposal.Action.HOLD) return;
        if (!settings.current().isAutoExecute()) return;
        KiwoomProposalOrderService.Result result = orders.autoExecute(proposal.getId());
        if (result.success()) {
            events.publishEvent("order", "자동 전송 정책으로 주문을 전송했습니다: " + proposal.getStockCode());
        } else {
            events.publishEvent(
                    "strategy",
                    "자동 전송 건너뜀: " + proposal.getStockCode() + " (" + result.message() + ")");
            log.info(
                    "[자동매매][자동 주문 건너뜀] {} {}({}), 사유={}",
                    actionLabel(proposal.getAction()),
                    proposal.getStockName(),
                    proposal.getStockCode(),
                    result.message());
        }
    }

    /** 실시간 시세(0B) 구독 대상 — 보유 종목 ∪ 스윙 후보. 잔고 조회 실패 시 후보만 사용한다. */
    public List<String> subscriptionCodes() {
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        try {
            for (KiwoomTradeService.Holding h :
                    trade.parseHoldings(trade.getBalance().block(Duration.ofSeconds(5)))) {
                codes.add(h.code());
            }
        } catch (Exception ignored) {
            // 잔고 조회 실패는 구독 대상 축소로만 반영한다.
        }
        currentSwingCandidates().forEach(c -> codes.add(c.bareCode()));
        return List.copyOf(codes);
    }

    /** 지금 유니버스에 편입되는 스윙 후보 — 컨트롤러의 읽기전용 조회(/universe)와 실시간 구독 대상이 이걸 공유한다. */
    public List<KrxOpenApiService.KrSwingCandidate> currentSwingCandidates() {
        return fetchCatalystCandidates().stream()
                .map(ShortSwingCandidateService.KrCandidateCatalyst::candidate)
                .toList();
    }

    /** DART 공시·뉴스 촉매까지 확인된 후보 목록. 조회 실패는 빈 목록으로 처리한다(이후 BUY 검증은 자연히 전부 막힌다). */
    private List<ShortSwingCandidateService.KrCandidateCatalyst> fetchCatalystCandidates() {
        try {
            var s = settings.current();
            if (s.getSwingMinChangePercent() == 2.0
                    && s.getSwingMaxChangePercent() == 8.0
                    && s.getSwingMinVolumeRatio() == 2.0)
                return catalystService.getKrCandidatesWithCatalysts(SWING_CANDIDATE_LIMIT);
            return catalystService.getKrCandidatesWithCatalysts(
                    SWING_CANDIDATE_LIMIT,
                    s.getSwingMinChangePercent(),
                    s.getSwingMinVolumeRatio(),
                    s.getSwingMaxChangePercent());
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 공시 1건 + 뉴스 1건까지만 요약한다 — 프롬프트 비대화 방지. 근거가 없으면 미확인임을 명시한다. */
    private String describeCatalyst(ShortSwingCandidateService.KrCandidateCatalyst cc) {
        List<String> parts = new ArrayList<>();
        if (!cc.disclosures().isEmpty()) {
            var d = cc.disclosures().get(0);
            parts.add(d.date() + " 공시: " + d.title());
        }
        if (!cc.news().isEmpty()) {
            var n = cc.news().get(0);
            parts.add("뉴스: " + n.title() + " (" + n.source() + ")");
        }
        if (!parts.isEmpty()) return String.join(" / ", parts);
        return cc.status() == ShortSwingCandidateService.CatalystStatus.UNAVAILABLE
                ? "촉매 조회 불가(API 제한·장애, 자동매수 차단 대상)"
                : "확인된 촉매 없음(자동매수 차단 대상)";
    }

    private DecisionValidation validated(
            JsonNode d,
            Map<String, String> universe,
            Map<String, Long> currentPrices,
            Map<String, KrxOpenApiService.KrSwingCandidate> swingCandidates,
            Map<String, ShortSwingCandidateService.CatalystStatus> catalystStatuses,
            long deposit) {
        String code = d == null ? null : d.path("stockCode").asText(null);
        try {
            if (code == null || !code.matches("\\d{6}"))
                return DecisionValidation.rejected(code, ValidationFailure.INVALID_STOCK_CODE);
            if (!universe.containsKey(code))
                return DecisionValidation.rejected(code, ValidationFailure.OUTSIDE_UNIVERSE);
            KiwoomTradeProposal.Action action;
            try {
                action = KiwoomTradeProposal.Action.valueOf(d.path("action").asText());
            } catch (Exception e) {
                return DecisionValidation.rejected(code, ValidationFailure.INVALID_ACTION);
            }
            int confidence = parseConfidence(d.path("confidence"));
            String aiReason = d.path("reason").asText("");
            if (action == KiwoomTradeProposal.Action.SELL) {
                log.warn(
                        "[자동매매][AI 임의 매도 차단] {}({}), AI 사유={}, 처리=팝업의 익절·손절·최대 보유기간 규칙에 따른 서버 청산만 허용",
                        universe.get(code),
                        code,
                        trim(aiReason));
                action = KiwoomTradeProposal.Action.HOLD;
                aiReason =
                        "AI 매도 제안은 실행하지 않습니다. 매도는 전략 설정의 익절·손절·최대 보유기간 조건으로 자동 관리합니다. AI 원문: "
                                + trim(aiReason);
            }
            if (action == KiwoomTradeProposal.Action.BUY) {
                KrxOpenApiService.KrSwingCandidate candidate = swingCandidates.get(code);
                if (candidate == null || !matchesCurrentBuyRules(candidate, settings.current()))
                    return DecisionValidation.rejected(code, ValidationFailure.BUY_RULE_MISMATCH);
                if (settings.current().isRequireCatalystForAutoBuy()) {
                    ShortSwingCandidateService.CatalystStatus catalystStatus =
                            catalystStatuses.getOrDefault(
                                    code, ShortSwingCandidateService.CatalystStatus.UNAVAILABLE);
                    if (catalystStatus == ShortSwingCandidateService.CatalystStatus.NOT_FOUND)
                        return DecisionValidation.rejected(
                                code, ValidationFailure.CATALYST_NOT_FOUND);
                    if (catalystStatus == ShortSwingCandidateService.CatalystStatus.UNAVAILABLE)
                        return DecisionValidation.rejected(
                                code, ValidationFailure.CATALYST_UNAVAILABLE);
                }
            }
            int qty = d.path("quantity").asInt();
            if (action != KiwoomTradeProposal.Action.HOLD && qty <= 0)
                return DecisionValidation.rejected(code, ValidationFailure.INVALID_QUANTITY);
            Long price = null;
            if (action != KiwoomTradeProposal.Action.HOLD) {
                long p0 = d.path("limitPrice").asLong();
                if (p0 <= 0)
                    return DecisionValidation.rejected(code, ValidationFailure.INVALID_PRICE);
                String market =
                        swingCandidates.containsKey(code)
                                ? swingCandidates.get(code).market()
                                : "KOSPI";
                if (!KiwoomPriceRules.isValidLimitPrice(p0, market))
                    return DecisionValidation.rejected(code, ValidationFailure.INVALID_TICK_SIZE);
                long reference =
                        swingCandidates.containsKey(code)
                                ? Math.round(swingCandidates.get(code).closePrice())
                                : currentPrices.getOrDefault(code, 0L);
                if (reference <= 0)
                    return DecisionValidation.rejected(
                            code, ValidationFailure.PRICE_REFERENCE_MISSING);
                if (exceedsPriceDeviation(p0, reference))
                    return DecisionValidation.rejected(code, ValidationFailure.PRICE_DEVIATION);
                price = p0;
            }
            var s = settings.current();
            if (action == KiwoomTradeProposal.Action.BUY) {
                // AI가 예산보다 큰 수량을 제안해도 전체를 버리지 않고 1회 주문 한도·예수금 비율 안으로
                // 수량을 깎아서 살린다 — 매번 통째로 거부되면 좋은 후보를 놓치게 된다.
                long cap =
                        Math.min(
                                props.getStrategy().getMaxOrderAmount(),
                                Math.round(deposit * s.getMaxBuyDepositPercent() / 100.0));
                long maxQtyByCap = price > 0 ? cap / price : 0;
                if (maxQtyByCap <= 0)
                    return DecisionValidation.rejected(code, ValidationFailure.INSUFFICIENT_BUDGET);
                qty = (int) Math.min(qty, maxQtyByCap);
            }
            KiwoomTradeProposal p = new KiwoomTradeProposal();
            p.setAction(action);
            p.setStockCode(code);
            p.setStockName(d.path("stockName").asText(universe.get(code)));
            p.setQuantity(Math.max(qty, 0));
            p.setConfidence(confidence);
            p.setReason(aiReason);
            p.setOrderType(KiwoomTradeProposal.OrderType.LIMIT);
            if (price != null) {
                p.setLimitPrice(price);
                p.setStopLossPrice(
                        Math.max(1, Math.round(price * (100 - s.getSwingStopLossPercent()) / 100)));
                p.setTakeProfitPrice(
                        Math.max(
                                1,
                                Math.round(price * (100 + s.getSwingTakeProfitPercent()) / 100)));
                p.setMaxHoldingDays(s.getSwingMaxHoldingDays());
            }
            return DecisionValidation.accepted(code, p);
        } catch (Exception e) {
            return DecisionValidation.rejected(code, ValidationFailure.MALFORMED);
        }
    }

    private boolean matchesCurrentBuyRules(
            KrxOpenApiService.KrSwingCandidate candidate,
            com.hyunchang.webapp.entity.KiwoomStrategySettings current) {
        return candidate.changePercent() >= current.getSwingMinChangePercent()
                && candidate.changePercent() <= current.getSwingMaxChangePercent()
                && candidate.volumeRatio() >= current.getSwingMinVolumeRatio();
    }

    /** 일부 모델이 0~100 정수 대신 0.0~1.0 비율로 응답하는 경우를 보정한다 (예: 0.82 → 82). */
    private int parseConfidence(JsonNode node) {
        double raw = node.asDouble(0);
        double scaled = raw > 0 && raw <= 1 ? raw * 100 : raw;
        return Math.max(0, Math.min(100, (int) Math.round(scaled)));
    }

    private boolean exceedsPriceDeviation(long price, long reference) {
        double maximum = props.getStrategy().getMaxOrderPriceDeviationPercent();
        return maximum > 0 && Math.abs(price - reference) * 100.0 / reference > maximum;
    }

    private String dailyLossTriggerDetail(
            KiwoomAutoTradeState.DailyLossStatus loss, double limitPercent, String assetSource) {
        if (loss == null) return String.format("한도=%.2f%%, 자산 계산=%s", limitPercent, assetSource);
        return String.format(
                "기준자산=%,d원, 순입출금=%+,d원, 보정기준=%,d원, 현재자산=%,d원, 손실=%,d원(%.2f%%), 한도=%.2f%%, 자산 계산=%s",
                loss.baseAsset(),
                loss.netCashFlow() - loss.baseNetCashFlow(),
                loss.adjustedBaseAsset(),
                loss.lastAsset(),
                loss.drawdown(),
                loss.drawdownPercent(),
                limitPercent,
                assetSource);
    }

    private void applyGuardFlags(KiwoomTradeProposal p, long deposit) {
        if (p.getAction() == KiwoomTradeProposal.Action.HOLD) return;
        List<String> flags = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now(KST);
        long amount = p.getLimitPrice() == null ? 0 : p.getLimitPrice() * p.getQuantity();
        if (!KiwoomMarketHours.isOpen()) flags.add("MARKET_CLOSED");
        if (amount > props.getStrategy().getMaxOrderAmount()) flags.add("MAX_ORDER_AMOUNT");
        if (p.getAction() == KiwoomTradeProposal.Action.BUY) {
            if (amount > deposit) flags.add("INSUFFICIENT_DEPOSIT");
            long buyBudget =
                    Math.round(deposit * settings.current().getMaxBuyDepositPercent() / 100.0);
            if (amount > buyBudget) flags.add("MAX_BUY_BUDGET");
            if (state.isDailyLossTriggered()) flags.add("DAILY_LOSS_LIMIT");
            LocalDateTime start = now.toLocalDate().atStartOfDay();
            long todayFilledBuys =
                    proposals.countByActionInAndStatusInAndCreatedAtGreaterThanEqual(
                            List.of(KiwoomTradeProposal.Action.BUY),
                            List.of(
                                    KiwoomTradeProposal.Status.PARTIALLY_FILLED,
                                    KiwoomTradeProposal.Status.FILLED),
                            start);
            if (todayFilledBuys >= settings.current().getDailyMaxProposals())
                flags.add("DAILY_LIMIT");
        }
        if (proposals.existsByStockCodeAndActionInAndStatusInAndOrderedAtGreaterThanEqual(
                p.getStockCode(),
                List.of(KiwoomTradeProposal.Action.BUY, KiwoomTradeProposal.Action.SELL),
                REORDER_COOLDOWN_STATUSES,
                now.minusMinutes(props.getStrategy().getCooldownMinutes())))
            flags.add("SYMBOL_COOLDOWN");
        p.setGuardFlags(String.join(",", flags));
    }

    private String promptLine(KiwoomTradeService.Holding h) {
        return h.name()
                + "("
                + h.code()
                + ") 보유 "
                + h.quantity()
                + "주 · 평단 "
                + String.format("%,d", h.avgPrice())
                + "원 · 현재가 "
                + String.format("%,d", h.curPrice())
                + "원 · 손익 "
                + h.plPct()
                + "%";
    }

    private String render(
            long deposit,
            List<String> holdingLines,
            List<String> candidateLines,
            String swing,
            String guardRules,
            int maxLines) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("현재시각", LocalDateTime.now(KST).toString());
        vars.put("예수금", String.format("%,d", deposit));
        vars.put("보유종목", joinCapped(holdingLines, maxLines, "보유 종목 없음"));
        vars.put("매매후보", joinCapped(candidateLines, maxLines, "매매 후보 없음"));
        vars.put("스윙지표", capLines(swing, maxLines));
        vars.put("하드가드규칙", guardRules);
        var s = settings.current();
        return prompts.render(AiPromptCatalog.KIWOOM_TRADE_STRATEGY, vars)
                + "\n\n[현재 적용 중 후보 기준 — 프롬프트 안의 고정 수치보다 우선]\n"
                + "신규 BUY는 당일 상승률 +"
                + s.getSwingMinChangePercent()
                + "% 이상 +"
                + s.getSwingMaxChangePercent()
                + "% 이하이고 20일 평균 대비 거래량 "
                + s.getSwingMinVolumeRatio()
                + "배 이상인 후보 안에서만 판단하세요. 이보다 낮으면 HOLD를 선택하세요."
                + runtimeTradingRules(s, deposit);
    }

    /**
     * The settings modal is the source of truth for numerical strategy rules. This block is
     * appended after an editable instruction so a stale custom prompt cannot silently override live
     * settings.
     */
    private String runtimeTradingRules(
            com.hyunchang.webapp.entity.KiwoomStrategySettings s, long deposit) {
        long buyBudget = Math.round(deposit * s.getMaxBuyDepositPercent() / 100.0);
        return "\n\n[현재 적용 중인 매매 규칙 — 이 블록이 모든 지침의 고정 수치보다 우선]\n"
                + "아래 수치는 ‘매매 규칙 설정’ 팝업에서 저장한 실제 적용값입니다. 위 지침에 다른 숫자(예: 거래량 2배, 상승률 +2%, 상승률 상한 +8%)가 있어도 그 숫자를 추가 조건으로 적용하지 마세요.\n"
                + "- 신규 BUY 후보: 당일 상승률 +"
                + s.getSwingMinChangePercent()
                + "% 이상 +"
                + s.getSwingMaxChangePercent()
                + "% 이하, 20일 평균 대비 거래량 "
                + s.getSwingMinVolumeRatio()
                + "배 이상인 후보 목록 안에서만 판단\n"
                + "- 위 상승률 범위를 벗어난 종목은 급등 추격 여부와 관계없이 신규 BUY 후보에서 제외\n"
                + "- 공시·뉴스 촉매는 신뢰도를 높이는 근거이지 후보 제외의 필수 조건은 아님. 근거가 약하면 HOLD를 우선\n"
                + "- 자동 주문 신뢰도 기준: "
                + s.getAutoExecuteMinConfidence()
                + "% 이상\n"
                + "- 1회 매수 한도: 예수금의 "
                + s.getMaxBuyDepositPercent()
                + "% 이내 (현재 "
                + String.format("%,d", buyBudget)
                + "원)\n"
                + "- 손절/익절/최대 보유기간: -"
                + s.getSwingStopLossPercent()
                + "% / +"
                + s.getSwingTakeProfitPercent()
                + "% / "
                + s.getSwingMaxHoldingDays()
                + "거래일\n"
                + "- 보유 종목 매도: 위 손절·익절·최대 보유기간 조건을 서버가 자동 집행하며 AI 임의 SELL은 실행하지 않음\n"
                + "이 규칙과 서버 검증을 통과한 제안만 실제 주문 전송 대상이 됩니다.";
    }

    /** AI가 서버 강제 한도 안에서 수량을 제안하도록 규칙을 프롬프트에 명시한다. */
    private String guardRules(long deposit) {
        KiwoomProperties.Strategy st = props.getStrategy();
        var s = settings.current();
        long buyBudget = Math.round(deposit * s.getMaxBuyDepositPercent() / 100.0);
        return "1회 주문 금액 한도: "
                + String.format("%,d", st.getMaxOrderAmount())
                + "원 (수량×지정가가 이 금액을 넘으면 안 됨)\n"
                + "매수 1건당 예수금의 "
                + s.getMaxBuyDepositPercent()
                + "% 이내 (현재 "
                + String.format("%,d", buyBudget)
                + "원)\n"
                + "하루 매수·매도 제안 한도: "
                + s.getDailyMaxProposals()
                + "건\n"
                + "동일 종목 재제안 쿨다운: "
                + st.getCooldownMinutes()
                + "분\n"
                + "자동매수 촉매 확인: "
                + (s.isRequireCatalystForAutoBuy() ? "필수(확인된 공시·뉴스가 없거나 조회 불가면 HOLD)" : "선택")
                + "\n"
                + "보유 종목 매도는 전략 설정의 손절·익절·최대 보유기간 조건만 서버가 자동 집행";
    }

    private String joinCapped(List<String> lines, int maxLines, String emptyText) {
        if (lines.isEmpty()) return emptyText;
        List<String> capped = lines.size() > maxLines ? lines.subList(0, maxLines) : lines;
        String joined = String.join("\n", capped);
        return lines.size() > maxLines
                ? joined + "\n(외 " + (lines.size() - maxLines) + "종목)"
                : joined;
    }

    private String capLines(String text, int maxLines) {
        String[] lines = text.split("\n");
        if (lines.length <= maxLines) return text;
        return String.join("\n", java.util.Arrays.copyOf(lines, maxLines));
    }

    private Map<String, KrxOpenApiService.KrSwingCandidate> indexByCode(
            List<KrxOpenApiService.KrSwingCandidate> candidates) {
        Map<String, KrxOpenApiService.KrSwingCandidate> indexed = new LinkedHashMap<>();
        for (KrxOpenApiService.KrSwingCandidate c : candidates) indexed.put(c.bareCode(), c);
        return indexed;
    }

    private String swingSignals(
            Map<String, KrxOpenApiService.KrSwingCandidate> swingCandidates,
            Set<String> universeCodes) {
        StringBuilder text = new StringBuilder();
        for (String code : universeCodes) {
            KrxOpenApiService.KrSwingCandidate c = swingCandidates.get(code);
            if (c != null) {
                text.append(code)
                        .append(" 등락 ")
                        .append(c.changePercent())
                        .append("% · 거래량 20일평균 대비 ")
                        .append(c.volumeRatio())
                        .append("배\n");
            }
        }
        return text.length() == 0 ? "검증된 스윙 신호가 없습니다. BUY를 제안하지 마세요." : text.toString();
    }

    private JsonNode parse(String s) {
        try {
            int a = s.indexOf('{');
            int b = s.lastIndexOf('}');
            return a < 0 || b < a ? null : json.readTree(s.substring(a, b + 1));
        } catch (Exception e) {
            return null;
        }
    }

    /** KrSwingCandidate가 이미 가진 시세로 문자열을 만든다 — 추가 API 호출이 없다. catalystText가 있으면 근거를 덧붙인다. */
    private String candidateLine(KrxOpenApiService.KrSwingCandidate c, String catalystText) {
        String base =
                c.name()
                        + "("
                        + c.bareCode()
                        + ") 현재가 "
                        + String.format("%,d", Math.round(c.closePrice()))
                        + "원 ("
                        + (c.changePercent() >= 0 ? "+" : "")
                        + c.changePercent()
                        + "%) · 거래량 20일평균 대비 "
                        + c.volumeRatio()
                        + "배";
        return catalystText == null || catalystText.isBlank()
                ? base
                : base + " · 근거: " + catalystText;
    }

    private long number(JsonNode n, String... names) {
        if (n != null) for (String x : names) if (n.has(x)) return n.path(x).asLong();
        return 0;
    }

    private String trim(String s) {
        return s == null ? "unknown" : s.substring(0, Math.min(s.length(), 500));
    }

    private enum ValidationFailure {
        INVALID_STOCK_CODE("종목코드 형식 오류"),
        OUTSIDE_UNIVERSE("현재 유니버스 외 종목"),
        INVALID_ACTION("매매 행동 형식 오류"),
        DUPLICATE_DECISION("동일 종목 중복 판단"),
        BUY_RULE_MISMATCH("현재 매수 후보 규칙 불일치"),
        CATALYST_NOT_FOUND("확인된 촉매 없음"),
        CATALYST_UNAVAILABLE("촉매 조회 불가"),
        INVALID_QUANTITY("주문 수량 오류"),
        INVALID_PRICE("지정가 누락 또는 오류"),
        INVALID_TICK_SIZE("호가 단위 불일치"),
        PRICE_REFERENCE_MISSING("기준 시세 없음"),
        PRICE_DEVIATION("현재가 대비 지정가 편차 초과"),
        INSUFFICIENT_BUDGET("1주 매수 예산 부족"),
        MALFORMED("AI 응답 형식 오류");

        private final String description;

        ValidationFailure(String description) {
            this.description = description;
        }

        String description() {
            return description;
        }
    }

    private record DecisionValidation(
            String stockCode,
            KiwoomTradeProposal proposal,
            ValidationFailure failure,
            boolean accepted) {
        static DecisionValidation accepted(String stockCode, KiwoomTradeProposal proposal) {
            return new DecisionValidation(stockCode, proposal, null, true);
        }

        static DecisionValidation rejected(String stockCode, ValidationFailure failure) {
            return new DecisionValidation(stockCode, null, failure, false);
        }
    }

    public record DecisionResult(Long runId, String status, int proposalCount) {}
}
