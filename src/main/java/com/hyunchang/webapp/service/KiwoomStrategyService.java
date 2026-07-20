package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.dto.StockPriceDto;
import com.hyunchang.webapp.entity.KiwoomStrategyRun;
import com.hyunchang.webapp.entity.KiwoomTradeProposal;
import com.hyunchang.webapp.entity.KiwoomWatchItem;
import com.hyunchang.webapp.repository.KiwoomStrategyRunRepository;
import com.hyunchang.webapp.repository.KiwoomTradeProposalRepository;
import com.hyunchang.webapp.repository.KiwoomWatchItemRepository;
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
 * 키움 자동매매 전략 엔진 — 계좌 잔고·관심종목·스윙지표를 모아 AI에 구조화 JSON 판단을 요청하고, 제안(proposal)으로 저장하는 데까지만 담당한다. 주문 전송은
 * KiwoomProposalOrderService가 별도의 승인·사전점검 게이트를 거쳐 수행한다.
 */
@Service
public class KiwoomStrategyService {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 압축 프롬프트에서 보유·관심·스윙 목록에 남기는 최대 줄 수 (Groq 8000자 한도 대응) */
    private static final int COMPACT_MAX_LINES = 10;

    private final KiwoomProperties props;
    private final KiwoomTradeService trade;
    private final KiwoomAutoTradeState state;
    private final StockService stocks;
    private final AiPromptService prompts;
    private final AiProviderChain ai;
    private final ObjectMapper json;
    private final KiwoomWatchItemRepository watch;
    private final KiwoomStrategyRunRepository runs;
    private final KiwoomTradeProposalRepository proposals;
    private final KiwoomWebsocketClient events;
    private final KiwoomProposalOrderService orders;
    private final KrxOpenApiService krx;
    private final KiwoomStrategySettingsService settings;
    private final KiwoomStrategyAuditService audit;

    public KiwoomStrategyService(
            KiwoomProperties props,
            KiwoomTradeService trade,
            KiwoomAutoTradeState state,
            StockService stocks,
            AiPromptService prompts,
            AiProviderChain ai,
            ObjectMapper json,
            KiwoomWatchItemRepository watch,
            KiwoomStrategyRunRepository runs,
            KiwoomTradeProposalRepository proposals,
            KiwoomWebsocketClient events,
            KiwoomProposalOrderService orders,
            KrxOpenApiService krx,
            KiwoomStrategySettingsService settings,
            KiwoomStrategyAuditService audit) {
        this.props = props;
        this.trade = trade;
        this.state = state;
        this.stocks = stocks;
        this.prompts = prompts;
        this.ai = ai;
        this.json = json;
        this.watch = watch;
        this.runs = runs;
        this.proposals = proposals;
        this.events = events;
        this.orders = orders;
        this.krx = krx;
        this.settings = settings;
        this.audit = audit;
    }

    @Scheduled(cron = "0 0/30 9-15 * * MON-FRI", zone = "Asia/Seoul")
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

            // 유니버스 = 실계좌 보유 종목 ∪ 관심종목. 보유 종목이 있어야 SELL 판단이 가능하다.
            List<KiwoomTradeService.Holding> holdings = trade.parseHoldings(balance);
            Map<String, String> universe = new LinkedHashMap<>();
            Map<String, Integer> sellableQty = new HashMap<>();
            List<String> holdingLines = new ArrayList<>();
            for (KiwoomTradeService.Holding h : holdings) {
                universe.put(h.code(), h.name());
                sellableQty.put(h.code(), h.sellable());
                holdingLines.add(promptLine(h));
            }
            List<String> watchLines = new ArrayList<>();
            for (KiwoomWatchItem item : watch.findAll()) {
                universe.putIfAbsent(item.getStockCode(), item.getStockName());
                if (!sellableQty.containsKey(item.getStockCode())) {
                    watchLines.add(
                            quoteLine(item.getStockCode(), item.getStockName(), item.getNote()));
                }
            }
            String swing = swingSignals(universe.keySet());
            String guardRules = guardRules(deposit);

            String prompt =
                    render(deposit, holdingLines, watchLines, swing, guardRules, Integer.MAX_VALUE);
            String compactPrompt =
                    render(deposit, holdingLines, watchLines, swing, guardRules, COMPACT_MAX_LINES);
            run.setPromptChars(prompt.length());

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
                KiwoomTradeProposal p = validated(d, universe, sellableQty, unique);
                if (p == null) continue;
                applyGuardFlags(p, deposit);
                p.setRun(run);
                proposals.save(p);
                saved++;
                if ("SCHEDULE".equals(by)) autoSubmit(p);
            }
            state.markRun();
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

    /** 실시간 시세(0B) 구독 대상 — 보유 종목 ∪ 관심종목. 잔고 조회 실패 시 관심종목만 사용한다. */
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
        watch.findAll().forEach(item -> codes.add(item.getStockCode()));
        return List.copyOf(codes);
    }

    private KiwoomTradeProposal validated(
            JsonNode d,
            Map<String, String> universe,
            Map<String, Integer> sellableQty,
            Set<String> unique) {
        try {
            String code = d.path("stockCode").asText();
            KiwoomTradeProposal.Action action =
                    KiwoomTradeProposal.Action.valueOf(d.path("action").asText());
            if (!code.matches("\\d{6}")
                    || !universe.containsKey(code)
                    || !unique.add(code)
                    || d.path("confidence").asInt(-1) < 0
                    || d.path("confidence").asInt() > 100) return null;
            if (action == KiwoomTradeProposal.Action.BUY && !hasVerifiedSwingSignal(code))
                return null;
            int qty = d.path("quantity").asInt();
            if (action != KiwoomTradeProposal.Action.HOLD && qty <= 0) return null;
            if (action == KiwoomTradeProposal.Action.SELL) {
                Integer held = sellableQty.get(code);
                if (held == null || held <= 0) return null;
                qty = Math.min(qty, held);
            }
            KiwoomTradeProposal p = new KiwoomTradeProposal();
            p.setAction(action);
            p.setStockCode(code);
            p.setStockName(d.path("stockName").asText(universe.get(code)));
            p.setQuantity(Math.max(qty, 0));
            p.setConfidence(d.path("confidence").asInt());
            p.setReason(d.path("reason").asText(""));
            p.setOrderType(KiwoomTradeProposal.OrderType.LIMIT);
            if (action != KiwoomTradeProposal.Action.HOLD) {
                long price = d.path("limitPrice").asLong();
                if (price <= 0) return null;
                var s = settings.current();
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
        if (today >= props.getStrategy().getDailyMaxProposals()) flags.add("DAILY_LIMIT");
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
            List<String> watchLines,
            String swing,
            String guardRules,
            int maxLines) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("현재시각", LocalDateTime.now(KST).toString());
        vars.put("예수금", String.format("%,d", deposit));
        vars.put("보유종목", joinCapped(holdingLines, maxLines, "보유 종목 없음"));
        vars.put("관심종목", joinCapped(watchLines, maxLines, "관심 종목 없음"));
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
                + st.getDailyMaxProposals()
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

    private String swingSignals(Set<String> universeCodes) {
        try {
            Map<String, KrxOpenApiService.KrSwingCandidate> indexed = new HashMap<>();
            for (KrxOpenApiService.KrSwingCandidate c : krx.getShortSwingCandidates(200)) {
                indexed.put(c.symbol().replaceAll("\\.(KS|KQ)$", ""), c);
            }
            StringBuilder text = new StringBuilder();
            for (String code : universeCodes) {
                KrxOpenApiService.KrSwingCandidate c = indexed.get(code);
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
        } catch (Exception e) {
            return "스윙 신호 조회에 실패했습니다. BUY를 제안하지 마세요.";
        }
    }

    private boolean hasVerifiedSwingSignal(String code) {
        try {
            return krx.getShortSwingCandidates(200).stream()
                    .anyMatch(c -> code.equals(c.symbol().replaceAll("\\.(KS|KQ)$", "")));
        } catch (Exception e) {
            return false;
        }
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

    private String quoteLine(String code, String name, String note) {
        StockPriceDto q = stocks.getQuote(code, "KR");
        String priceText =
                q == null
                        ? "시세 미확인"
                        : "현재가 "
                                + String.format("%,d", Math.round(q.getPrice()))
                                + "원 ("
                                + (q.getChangePercent() >= 0 ? "+" : "")
                                + q.getChangePercent()
                                + "%)";
        return name
                + "("
                + code
                + ") "
                + priceText
                + (note == null || note.isBlank() ? "" : " 메모: " + note);
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
