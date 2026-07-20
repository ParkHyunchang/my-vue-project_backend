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
        lenient().when(trade.getDeposit()).thenReturn(Mono.just(emptyNode));
        lenient().when(trade.getBalance()).thenReturn(Mono.just(emptyNode));
        lenient().when(trade.totalEvaluationAmount(any())).thenReturn(0L);
        lenient().when(trade.parseHoldings(any())).thenReturn(List.of());
        lenient().when(prompts.render(anyString(), any())).thenReturn("prompt");
        lenient().when(runs.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(proposals.save(any())).thenAnswer(inv -> inv.getArgument(0));
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
        String json =
                "{\"marketView\":\"m\",\"decisions\":[{\"action\":\"BUY\",\"stockCode\":\""
                        + code
                        + "\",\"stockName\":\"n\",\"quantity\":1,\"orderType\":\"LIMIT\","
                        + "\"limitPrice\":70000,\"confidence\":80,\"reason\":\"r\"}]}";
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
