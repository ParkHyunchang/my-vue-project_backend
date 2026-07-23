package com.hyunchang.webapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.entity.KiwoomStrategySettings;
import com.hyunchang.webapp.entity.KiwoomTradeProposal;
import com.hyunchang.webapp.repository.KiwoomStrategyRunRepository;
import com.hyunchang.webapp.repository.KiwoomTradeProposalRepository;
import com.hyunchang.webapp.service.ai.AiProviderChain;
import com.hyunchang.webapp.service.prompt.AiPromptService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class KiwoomStrategyServiceTest {

    @Mock private KiwoomTradeService trade;
    @Mock private KiwoomAutoTradeState state;
    @Mock private AiPromptService prompts;
    @Mock private AiProviderChain ai;
    @Mock private KiwoomStrategyRunRepository runs;
    @Mock private KiwoomTradeProposalRepository proposals;
    @Mock private KiwoomWebsocketClient events;
    @Mock private KiwoomProposalOrderService orders;
    @Mock private KrxOpenApiService krx;
    @Mock private KiwoomStrategySettingsService settingsService;
    @Mock private KiwoomStrategyAuditService audit;

    private KiwoomProperties props;
    private KiwoomStrategySettings settings;
    private KiwoomStrategyService service;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ObjectNode emptyNode = mapper.createObjectNode();

    @BeforeEach
    void setUp() {
        props = new KiwoomProperties();
        props.getStrategy().setMaxOrderAmount(10_000_000);
        props.getStrategy().setDailyMaxProposals(50);
        props.getStrategy().setCooldownMinutes(0);

        settings = new KiwoomStrategySettings();
        settings.setMaxBuyDepositPercent(100);
        settings.setSwingStopLossPercent(3);
        settings.setSwingTakeProfitPercent(6);
        settings.setSwingMaxHoldingDays(5);

        service =
                new KiwoomStrategyService(
                        props,
                        trade,
                        state,
                        prompts,
                        ai,
                        mapper,
                        runs,
                        proposals,
                        events,
                        orders,
                        krx,
                        settingsService,
                        audit);

        lenient().when(state.isEmergencyStopped()).thenReturn(false);
        lenient().when(state.tryStartDecision()).thenReturn(true);
        lenient().when(state.recordDailyLossCheck(anyLong(), anyLong())).thenReturn(false);
        lenient().when(state.isDailyLossTriggered()).thenReturn(false);
        lenient().when(settingsService.current()).thenReturn(settings);
        lenient().when(trade.getDeposit()).thenReturn(Mono.just(depositNode(100_000_000)));
        lenient().when(trade.getBalance()).thenReturn(Mono.just(emptyNode));
        lenient().when(trade.totalEvaluationAmount(any())).thenReturn(0L);
        lenient().when(trade.parseHoldings(any())).thenReturn(List.of());
        lenient().when(prompts.render(anyString(), any())).thenReturn("prompt");
        lenient().when(runs.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(proposals.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private com.fasterxml.jackson.databind.JsonNode depositNode(long amount) {
        ObjectNode node = mapper.createObjectNode();
        node.put("ord_alow_amt", amount);
        return node;
    }

    private KrxOpenApiService.KrSwingCandidate candidate(String code) {
        return new KrxOpenApiService.KrSwingCandidate(
                code + ".KS",
                "종목" + code,
                "KOSPI",
                70_000,
                4.0,
                1_000_000,
                300_000,
                3.5,
                LocalDate.now());
    }

    private AiProviderChain.ChainResult buyDecision(String code) {
        return decisionJson("BUY", code, 1, 70000, "80");
    }

    /** confidence는 raw JSON 리터럴을 그대로 끼워 넣는다 — 정수("80")·소수("0.82") 양쪽을 테스트하기 위함. */
    private AiProviderChain.ChainResult decisionJson(
            String action, String code, int qty, long price, String confidenceLiteral) {
        String json =
                "{\"marketView\":\"m\",\"decisions\":[{\"action\":\""
                        + action
                        + "\",\"stockCode\":\""
                        + code
                        + "\",\"stockName\":\"n\",\"quantity\":"
                        + qty
                        + ",\"orderType\":\"LIMIT\",\"limitPrice\":"
                        + price
                        + ",\"confidence\":"
                        + confidenceLiteral
                        + ",\"reason\":\"r\"}]}";
        return new AiProviderChain.ChainResult(true, "provider", "model", json, null, List.of());
    }

    @Test
    void buyOnSwingCandidateIsSaved() {
        when(krx.getShortSwingCandidates(20)).thenReturn(List.of(candidate("005930")));
        when(ai.analyze(anyString(), anyString(), eq(true))).thenReturn(buyDecision("005930"));

        service.runDecision("MANUAL");

        ArgumentCaptor<KiwoomTradeProposal> captor =
                ArgumentCaptor.forClass(KiwoomTradeProposal.class);
        verify(proposals).save(captor.capture());
        assertEquals("005930", captor.getValue().getStockCode());
        assertEquals(KiwoomTradeProposal.Action.BUY, captor.getValue().getAction());
    }

    @Test
    void buyOnHeldButNoLongerCandidateStockIsBlocked() {
        // 보유 중이지만 지금은 스윙 후보에서 빠진 종목 — 물타기 방지 가드가 막아야 한다.
        KiwoomTradeService.Holding held =
                new KiwoomTradeService.Holding("003550", "LG", 10, 10, 80_000, 78_000, -2.5);
        when(trade.parseHoldings(any())).thenReturn(List.of(held));
        when(krx.getShortSwingCandidates(20)).thenReturn(List.of(candidate("005930")));
        when(ai.analyze(anyString(), anyString(), eq(true))).thenReturn(buyDecision("003550"));

        service.runDecision("MANUAL");

        verify(proposals, never()).save(any());
    }

    @Test
    void confidenceAcceptsFractionalZeroToOneScale() {
        // 일부 모델이 0~100 정수 대신 0.0~1.0 비율로 confidence를 응답하는 경우를 보정해야 한다.
        when(krx.getShortSwingCandidates(20)).thenReturn(List.of(candidate("005930")));
        when(ai.analyze(anyString(), anyString(), eq(true)))
                .thenReturn(decisionJson("BUY", "005930", 1, 70000, "0.82"));

        service.runDecision("MANUAL");

        ArgumentCaptor<KiwoomTradeProposal> captor =
                ArgumentCaptor.forClass(KiwoomTradeProposal.class);
        verify(proposals).save(captor.capture());
        assertEquals(82, captor.getValue().getConfidence());
    }

    @Test
    void confidenceAcceptsPlainIntegerPercent() {
        when(krx.getShortSwingCandidates(20)).thenReturn(List.of(candidate("005930")));
        when(ai.analyze(anyString(), anyString(), eq(true)))
                .thenReturn(decisionJson("BUY", "005930", 1, 70000, "80"));

        service.runDecision("MANUAL");

        ArgumentCaptor<KiwoomTradeProposal> captor =
                ArgumentCaptor.forClass(KiwoomTradeProposal.class);
        verify(proposals).save(captor.capture());
        assertEquals(80, captor.getValue().getConfidence());
    }

    @Test
    void buyQuantityIsClampedToBudgetInsteadOfRejected() {
        // 예수금 100,000원 · 매수 비율 100% → 예산 100,000원. 가격 70,000원이면 최대 1주인데
        // AI는 5주를 요청 — 통째로 버리지 않고 1주로 깎아서 살려야 한다.
        when(trade.getDeposit()).thenReturn(Mono.just(depositNode(100_000)));
        when(krx.getShortSwingCandidates(20)).thenReturn(List.of(candidate("005930")));
        when(ai.analyze(anyString(), anyString(), eq(true)))
                .thenReturn(decisionJson("BUY", "005930", 5, 70000, "80"));

        service.runDecision("MANUAL");

        ArgumentCaptor<KiwoomTradeProposal> captor =
                ArgumentCaptor.forClass(KiwoomTradeProposal.class);
        verify(proposals).save(captor.capture());
        assertEquals(1, captor.getValue().getQuantity());
    }

    @Test
    void buyIsDroppedWhenBudgetCannotAffordEvenOneShare() {
        when(trade.getDeposit()).thenReturn(Mono.just(depositNode(1_000)));
        when(krx.getShortSwingCandidates(20)).thenReturn(List.of(candidate("005930")));
        when(ai.analyze(anyString(), anyString(), eq(true)))
                .thenReturn(decisionJson("BUY", "005930", 5, 70000, "80"));

        service.runDecision("MANUAL");

        verify(proposals, never()).save(any());
    }

    @Test
    void subscriptionCodesUnionsHoldingsAndCandidates() {
        KiwoomTradeService.Holding held =
                new KiwoomTradeService.Holding("003550", "LG", 5, 5, 50_000, 51_000, 2.0);
        when(trade.parseHoldings(any())).thenReturn(List.of(held));
        when(krx.getShortSwingCandidates(20)).thenReturn(List.of(candidate("005930")));

        assertEquals(List.of("003550", "005930"), service.subscriptionCodes());
    }

    @Test
    void subscriptionCodesFallBackToCandidatesWhenBalanceFails() {
        when(trade.getBalance()).thenThrow(new RuntimeException("network"));
        when(krx.getShortSwingCandidates(20)).thenReturn(List.of(candidate("005930")));

        assertEquals(List.of("005930"), service.subscriptionCodes());
    }
}
