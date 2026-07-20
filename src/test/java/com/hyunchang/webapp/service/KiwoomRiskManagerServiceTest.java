package com.hyunchang.webapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class KiwoomRiskManagerServiceTest {

    @Mock private KiwoomTradeService trade;
    @Mock private KiwoomAutoTradeState state;
    @Mock private KiwoomStrategySettingsService settingsService;
    @Mock private KiwoomTradeProposalRepository proposals;
    @Mock private KiwoomStrategyRunRepository runs;
    @Mock private KiwoomProposalOrderService orders;
    @Mock private KiwoomStrategyAuditService audit;
    @Mock private KiwoomWebsocketClient events;

    private KiwoomProperties props;
    private KiwoomStrategySettings settings;
    private KiwoomRiskManagerService service;
    private final ObjectNode emptyNode = new ObjectMapper().createObjectNode();

    @BeforeEach
    void setUp() {
        props = new KiwoomProperties();
        props.getStrategy().setMaxOrderAmount(500_000);

        settings = new KiwoomStrategySettings();
        settings.setRiskLoopEnabled(true);
        settings.setSwingStopLossPercent(3);
        settings.setSwingTakeProfitPercent(6);
        settings.setSwingMaxHoldingDays(5);
        settings.setDailyLossLimitAmount(0);
        settings.setAutoExecute(false);

        service =
                new KiwoomRiskManagerService(
                        props,
                        trade,
                        state,
                        settingsService,
                        proposals,
                        runs,
                        orders,
                        audit,
                        events);

        lenient().when(state.isEmergencyStopped()).thenReturn(false);
        lenient().when(state.tryStartDecision()).thenReturn(true);
        lenient().when(state.recordDailyLossCheck(anyLong(), anyLong())).thenReturn(false);
        lenient().when(state.isDailyLossTriggered()).thenReturn(false);
        lenient().when(settingsService.current()).thenReturn(settings);
        lenient().when(trade.getDeposit()).thenReturn(Mono.just(emptyNode));
        lenient().when(trade.getBalance()).thenReturn(Mono.just(emptyNode));
        lenient().when(trade.totalEvaluationAmount(any())).thenReturn(0L);
        lenient().when(runs.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient()
                .when(proposals.save(any()))
                .thenAnswer(
                        inv -> {
                            KiwoomTradeProposal p = inv.getArgument(0);
                            ReflectionTestUtils.setField(p, "id", 1L);
                            return p;
                        });
        lenient()
                .when(
                        proposals.existsByStockCodeAndActionAndStatusIn(
                                anyString(), any(), anyCollection()))
                .thenReturn(false);
        lenient()
                .when(
                        proposals.findFirstByStockCodeAndActionAndStatusOrderByCreatedAtDesc(
                                anyString(), any(), any()))
                .thenReturn(Optional.empty());
    }

    private void holdings(KiwoomTradeService.Holding... items) {
        when(trade.parseHoldings(any())).thenReturn(List.of(items));
    }

    private KiwoomTradeProposal savedProposal() {
        ArgumentCaptor<KiwoomTradeProposal> captor =
                ArgumentCaptor.forClass(KiwoomTradeProposal.class);
        verify(proposals).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void stopLossTriggersAtBoundary() {
        // 평단 70,000 · 손절 3% → 기준가 67,900. 현재가가 기준가 이하면 손절 청산.
        holdings(new KiwoomTradeService.Holding("005930", "삼성전자", 10, 10, 70_000, 67_900, -3.0));

        KiwoomRiskManagerService.RiskScanResult result = service.runRiskScan("MANUAL");

        assertEquals(1, result.proposalCount());
        KiwoomTradeProposal p = savedProposal();
        assertEquals(KiwoomTradeProposal.Action.SELL, p.getAction());
        assertEquals(100, p.getConfidence());
        assertEquals(67_900L, p.getLimitPrice().longValue());
        assertTrue(p.getReason().contains("STOP_LOSS"));
    }

    @Test
    void noTriggerJustAboveStopLossBoundary() {
        holdings(new KiwoomTradeService.Holding("005930", "삼성전자", 10, 10, 70_000, 67_901, -2.99));

        KiwoomRiskManagerService.RiskScanResult result = service.runRiskScan("MANUAL");

        assertEquals(0, result.proposalCount());
        verify(proposals, never()).save(any());
    }

    @Test
    void takeProfitTriggersAtBoundary() {
        // 평단 70,000 · 익절 6% → 기준가 74,200 이상이면 익절 청산.
        holdings(new KiwoomTradeService.Holding("005930", "삼성전자", 10, 10, 70_000, 74_200, 6.0));

        KiwoomRiskManagerService.RiskScanResult result = service.runRiskScan("MANUAL");

        assertEquals(1, result.proposalCount());
        assertTrue(savedProposal().getReason().contains("TAKE_PROFIT"));
    }

    @Test
    void timeExitAppliesOnlyToEngineBoughtHoldings() {
        // 손절·익절 범위 밖 + 엔진 FILLED BUY 이력이 6일 전 → 보유기간(5일) 초과 청산.
        holdings(new KiwoomTradeService.Holding("005930", "삼성전자", 10, 10, 70_000, 70_000, 0.0));
        KiwoomTradeProposal filledBuy = new KiwoomTradeProposal();
        ReflectionTestUtils.setField(filledBuy, "createdAt", LocalDateTime.now().minusDays(6));
        when(proposals.findFirstByStockCodeAndActionAndStatusOrderByCreatedAtDesc(
                        eq("005930"),
                        eq(KiwoomTradeProposal.Action.BUY),
                        eq(KiwoomTradeProposal.Status.FILLED)))
                .thenReturn(Optional.of(filledBuy));

        KiwoomRiskManagerService.RiskScanResult result = service.runRiskScan("MANUAL");

        assertEquals(1, result.proposalCount());
        assertTrue(savedProposal().getReason().contains("TIME_EXIT"));
    }

    @Test
    void manualHoldingIsNotTimeExited() {
        // 엔진 매수 이력이 없는 수동 매수 종목은 시간 청산 대상이 아니다.
        holdings(new KiwoomTradeService.Holding("005930", "삼성전자", 10, 10, 70_000, 70_000, 0.0));

        assertEquals(0, service.runRiskScan("MANUAL").proposalCount());
    }

    @Test
    void skipsWhenOpenSellExists() {
        holdings(new KiwoomTradeService.Holding("005930", "삼성전자", 10, 10, 70_000, 60_000, -14.3));
        when(proposals.existsByStockCodeAndActionAndStatusIn(
                        eq("005930"), eq(KiwoomTradeProposal.Action.SELL), anyCollection()))
                .thenReturn(true);

        assertEquals(0, service.runRiskScan("MANUAL").proposalCount());
        verify(proposals, never()).save(any());
    }

    @Test
    void quantityIsCappedByMaxOrderAmount() {
        // 한도 500,000 · 현재가 60,000 → 최대 8주. 매도가능 20주라도 8주 부분 청산.
        holdings(new KiwoomTradeService.Holding("005930", "삼성전자", 20, 20, 70_000, 60_000, -14.3));

        KiwoomRiskManagerService.RiskScanResult result = service.runRiskScan("MANUAL");

        assertEquals(1, result.proposalCount());
        KiwoomTradeProposal p = savedProposal();
        assertEquals(8, p.getQuantity());
        assertTrue(p.getReason().contains("부분 청산"));
    }

    @Test
    void disabledLoopOnlyPerformsDailyLossCheck() {
        settings.setRiskLoopEnabled(false);

        KiwoomRiskManagerService.RiskScanResult result = service.runRiskScan("MANUAL");

        assertEquals(0, result.proposalCount());
        verify(state).recordDailyLossCheck(anyLong(), anyLong());
        verify(trade, never()).parseHoldings(any());
    }

    @Test
    void autoExecuteIsInvokedWhenEnabled() {
        settings.setAutoExecute(true);
        holdings(new KiwoomTradeService.Holding("005930", "삼성전자", 10, 10, 70_000, 67_000, -4.3));
        when(orders.autoExecute(anyLong()))
                .thenReturn(new KiwoomProposalOrderService.Result(false, "dry-run", null));

        assertEquals(1, service.runRiskScan("MANUAL").proposalCount());
        verify(orders).autoExecute(anyLong());
    }
}
