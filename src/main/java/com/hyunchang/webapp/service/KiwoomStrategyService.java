package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.entity.KiwoomStrategyRun;
import com.hyunchang.webapp.entity.KiwoomTradeProposal;
import com.hyunchang.webapp.repository.KiwoomStrategyRunRepository;
import com.hyunchang.webapp.repository.KiwoomTradeProposalRepository;
import com.hyunchang.webapp.service.ai.AiProviderChain;
import com.hyunchang.webapp.service.prompt.AiPromptCatalog;
import com.hyunchang.webapp.service.prompt.AiPromptService;
import com.hyunchang.webapp.util.KiwoomMarketHours;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** 압축 프롬프트에서 보유·매매후보·스윙 목록에 남기는 최대 줄 수 (Groq 8000자 한도 대응) */
    private static final int COMPACT_MAX_LINES = 10;

    /** 유니버스에 편입되는 스윙 후보 상한 — ShortSwingCandidateService.MAX_SCREENED_CANDIDATES와 동일한 관례. */
    private static final int SWING_CANDIDATE_LIMIT = 20;
    private volatile String lastCandidateSignature;

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
            KiwoomStrategyAuditService audit) {
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
                // 긴급 중지 또는 이미 실행 중 — 다음 주기에 다시 시도한다.
            }
        }
    }

    public DecisionResult runDecision(String by) {
        if (state.isEmergencyStopped())
            throw new IllegalStateException("긴급 중지 상태에서는 전략 판단을 실행할 수 없습니다.");
        if (!state.tryStartDecision()) throw new IllegalStateException("이미 전략 판단이 실행 중입니다.");
        KiwoomStrategyRun run = new KiwoomStrategyRun();
        run.setTriggeredBy(
                "SCHEDULE".equals(by)
                        ? KiwoomStrategyRun.TriggeredBy.SCHEDULE
                        : KiwoomStrategyRun.TriggeredBy.MANUAL);
        try {
            events.publishEvent("strategy", "AI 전략 판단을 시작합니다.");
            if ("SCHEDULE".equals(by) && dailyProposalLimitReached()) {
                return skipped(run, "Daily proposal/order limit reached; AI call skipped.");
            }
            JsonNode depositNode = trade.getDeposit().block(Duration.ofSeconds(10));
            JsonNode balance = trade.getBalance().block(Duration.ofSeconds(10));
            long deposit = number(depositNode, "ord_alow_amt", "entr");

            // 일일 손실 한도 체크 — 이미 조회한 예수금·잔고를 재사용하므로 추가 API 호출이 없다.
            if (state.recordDailyLossCheck(
                    deposit + trade.totalEvaluationAmount(balance),
                    settings.current().getDailyLossLimitAmount())) {
                audit.log("DAILY_LOSS_TRIGGERED", null, "일일 손실 한도에 도달해 신규 매수를 차단합니다.");
                events.publishEvent("strategy", "일일 손실 한도 발동 — 오늘 남은 시간 동안 신규 매수를 차단합니다.");
            }

            // 유니버스 = 실계좌 보유 종목 ∪ KRX 자동 스캔 스윙 후보(최대 SWING_CANDIDATE_LIMIT개).
            // 사람이 등록하는 관심종목은 없다 — 보유 종목이 있어야 SELL 판단이 가능하다.
            List<KiwoomTradeService.Holding> holdings = trade.parseHoldings(balance);
            Map<String, String> universe = new LinkedHashMap<>();
            Map<String, Integer> sellableQty = new HashMap<>();
            List<String> holdingLines = new ArrayList<>();
            for (KiwoomTradeService.Holding h : holdings) {
                universe.put(h.code(), h.name());
                sellableQty.put(h.code(), h.sellable());
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
            List<String> candidateLines = new ArrayList<>();
            for (ShortSwingCandidateService.KrCandidateCatalyst cc : catalystCandidates) {
                KrxOpenApiService.KrSwingCandidate c = cc.candidate();
                universe.putIfAbsent(c.bareCode(), c.name());
                if (!sellableQty.containsKey(c.bareCode())) {
                    candidateLines.add(candidateLine(c, describeCatalyst(cc)));
                }
            }
            String swing = swingSignals(swingCandidates, universe.keySet());
            String guardRules = guardRules(deposit);
            String candidateSignature = candidateSignature(catalystCandidates);
            if ("SCHEDULE".equals(by)
                    && candidateSignature.equals(lastCandidateSignature)
                    && !holdingExitTriggered(holdings)) {
                return skipped(run, "No candidate change or holding stop-loss/take-profit signal; AI call skipped.");
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
            Set<String> unique = new HashSet<>();
            for (JsonNode d : root.path("decisions")) {
                KiwoomTradeProposal p =
                        validated(d, universe, sellableQty, swingCandidates, deposit, unique);
                if (p == null) continue;
                applyGuardFlags(p, deposit);
                p.setRun(run);
                proposals.save(p);
                saved++;
                if ("SCHEDULE".equals(by)) autoSubmit(p);
            }
            state.markRun();
            lastCandidateSignature = candidateSignature;
            events.publishEvent("strategy", "AI 전략 제안 " + saved + "건을 생성했습니다.");
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

    private boolean dailyProposalLimitReached() {
        long today = proposals.countByActionInAndCreatedAtGreaterThanEqual(
                List.of(KiwoomTradeProposal.Action.BUY, KiwoomTradeProposal.Action.SELL),
                LocalDateTime.now(KST).toLocalDate().atStartOfDay());
        return today >= settings.current().getDailyMaxProposals();
    }

    private boolean holdingExitTriggered(List<KiwoomTradeService.Holding> holdings) {
        var s = settings.current();
        return holdings.stream().anyMatch(h -> h.plPct() <= -s.getSwingStopLossPercent()
                || h.plPct() >= s.getSwingTakeProfitPercent());
    }

    private String candidateSignature(List<ShortSwingCandidateService.KrCandidateCatalyst> candidates) {
        return candidates.stream()
                .map(cc -> {
                    KrxOpenApiService.KrSwingCandidate c = cc.candidate();
                    return c.bareCode() + ':' + Math.round(c.changePercent() * 10)
                            + ':' + Math.round(c.volumeRatio() * 10) + ':' + describeCatalyst(cc);
                })
                .collect(java.util.stream.Collectors.joining("|"));
    }

    private DecisionResult skipped(KiwoomStrategyRun run, String reason) {
        run.setStatus(KiwoomStrategyRun.Status.SKIPPED);
        run.setErrorMessage(reason);
        runs.save(run);
        events.publishEvent("strategy", reason);
        return new DecisionResult(run.getId(), run.getStatus().name(), 0);
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
            return catalystService.getKrCandidatesWithCatalysts(SWING_CANDIDATE_LIMIT);
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
        return parts.isEmpty() ? "촉매 미확인(가격·거래량 신호만)" : String.join(" / ", parts);
    }

    private KiwoomTradeProposal validated(
            JsonNode d,
            Map<String, String> universe,
            Map<String, Integer> sellableQty,
            Map<String, KrxOpenApiService.KrSwingCandidate> swingCandidates,
            long deposit,
            Set<String> unique) {
        try {
            String code = d.path("stockCode").asText();
            KiwoomTradeProposal.Action action =
                    KiwoomTradeProposal.Action.valueOf(d.path("action").asText());
            int confidence = parseConfidence(d.path("confidence"));
            if (!code.matches("\\d{6}") || !universe.containsKey(code) || !unique.add(code))
                return null;
            if (action == KiwoomTradeProposal.Action.BUY && !swingCandidates.containsKey(code))
                return null;
            int qty = d.path("quantity").asInt();
            if (action != KiwoomTradeProposal.Action.HOLD && qty <= 0) return null;
            Long price = null;
            if (action != KiwoomTradeProposal.Action.HOLD) {
                long p0 = d.path("limitPrice").asLong();
                if (p0 <= 0) return null;
                price = p0;
            }
            var s = settings.current();
            if (action == KiwoomTradeProposal.Action.SELL) {
                Integer held = sellableQty.get(code);
                if (held == null || held <= 0) return null;
                qty = Math.min(qty, held);
            } else if (action == KiwoomTradeProposal.Action.BUY) {
                // AI가 예산보다 큰 수량을 제안해도 전체를 버리지 않고 1회 주문 한도·예수금 비율 안으로
                // 수량을 깎아서 살린다 — 매번 통째로 거부되면 좋은 후보를 놓치게 된다.
                long cap =
                        Math.min(
                                props.getStrategy().getMaxOrderAmount(),
                                Math.round(deposit * s.getMaxBuyDepositPercent() / 100.0));
                long maxQtyByCap = price > 0 ? cap / price : 0;
                if (maxQtyByCap <= 0) return null;
                qty = (int) Math.min(qty, maxQtyByCap);
            }
            KiwoomTradeProposal p = new KiwoomTradeProposal();
            p.setAction(action);
            p.setStockCode(code);
            p.setStockName(d.path("stockName").asText(universe.get(code)));
            p.setQuantity(Math.max(qty, 0));
            p.setConfidence(confidence);
            p.setReason(d.path("reason").asText(""));
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
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    /** 일부 모델이 0~100 정수 대신 0.0~1.0 비율로 응답하는 경우를 보정한다 (예: 0.82 → 82). */
    private int parseConfidence(JsonNode node) {
        double raw = node.asDouble(0);
        double scaled = raw > 0 && raw <= 1 ? raw * 100 : raw;
        return Math.max(0, Math.min(100, (int) Math.round(scaled)));
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
        }
        LocalDateTime start = now.toLocalDate().atStartOfDay();
        long today =
                proposals.countByActionInAndCreatedAtGreaterThanEqual(
                        List.of(KiwoomTradeProposal.Action.BUY, KiwoomTradeProposal.Action.SELL),
                        start);
        if (today >= settings.current().getDailyMaxProposals()) flags.add("DAILY_LIMIT");
        if (proposals.existsByStockCodeAndActionInAndCreatedAtGreaterThanEqual(
                p.getStockCode(),
                List.of(KiwoomTradeProposal.Action.BUY, KiwoomTradeProposal.Action.SELL),
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
        return prompts.render(AiPromptCatalog.KIWOOM_TRADE_STRATEGY, vars);
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
                + "SELL 수량은 보유 수량 이내";
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

    public record DecisionResult(Long runId, String status, int proposalCount) {}
}
