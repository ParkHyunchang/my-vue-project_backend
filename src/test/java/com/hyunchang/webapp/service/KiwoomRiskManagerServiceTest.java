package com.hyunchang.webapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
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
import com.hyunchang.webapp.service.kiwoom.KiwoomAutoTradeState;
import com.hyunchang.webapp.service.kiwoom.KiwoomWebsocketClient;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class KiwoomRiskManagerServiceTest {

    @Mock private KiwoomTradeService trade;
    @Mock private KiwoomAutoTradeState state;
    @Mock private KiwoomStrategySettingsService settingsService;
    @Mock private KiwoomStrategyAuditService audit;
    @Mock private KiwoomWebsocketClient events;

    private KiwoomStrategySettings settings;
    private KiwoomRiskManagerService service;
    private final ObjectNode emptyNode = new ObjectMapper().createObjectNode();

    @BeforeEach
    void setUp() {
        settings = new KiwoomStrategySettings();
        settings.setRiskLoopEnabled(true);
        settings.setDailyLossLimitPercent(0);

        service =
                new KiwoomRiskManagerService(
                        new KiwoomProperties(), trade, state, settingsService, audit, events);

        lenient().when(state.isEmergencyStopped()).thenReturn(false);
        lenient().when(state.tryStartDecision()).thenReturn(true);
        lenient().when(state.recordDailyLossCheck(anyLong(), anyLong(), anyDouble())).thenReturn(false);
        lenient().when(state.isDailyLossTriggered()).thenReturn(false);
        lenient().when(settingsService.current()).thenReturn(settings);
        lenient().when(trade.getDeposit()).thenReturn(Mono.just(emptyNode));
        lenient().when(trade.getBalance()).thenReturn(Mono.just(emptyNode));
        lenient()
                .when(trade.accountAsset(any(), any()))
                .thenReturn(new KiwoomTradeService.AccountAsset(0L, "테스트"));
        lenient().when(trade.parseHoldings(any())).thenReturn(List.of());
    }

    @Test
    void zeroDailyLossLimitDisablesTheGuardInsteadOfFallingBackToPercent() {
        assertEquals(0, service.dailyLossLimit(0));
        assertEquals(3, service.dailyLossLimit(3));
        assertEquals(30, service.dailyLossLimit(50));
    }

    @Test
    void adminCanResetDailyLossGuardUsingCurrentAccountAsset() {
        KiwoomAutoTradeState.DailyLossStatus reset =
                new KiwoomAutoTradeState.DailyLossStatus(
                        java.time.LocalDate.now(),
                        1_250_000,
                        0,
                        0,
                        1_250_000,
                        false,
                        LocalDateTime.now());
        when(trade.accountAsset(any(), any()))
                .thenReturn(new KiwoomTradeService.AccountAsset(1_250_000, "추정예탁자산"));
        when(state.resetDailyLossCheck(1_250_000, 0)).thenReturn(reset);

        KiwoomRiskManagerService.DailyLossResetResult result = service.resetDailyLossGuard();

        assertEquals(1_250_000, result.newBaseAsset());
        assertEquals("추정예탁자산", result.assetSource());
        verify(audit).log(eq("DAILY_LOSS_RESET"), eq(null), anyString());
        verify(events).publishEvent(eq("strategy"), anyString());
    }

    @Test
    void periodicRiskCheckNeverCreatesIndependentSellOrders() {
        when(trade.parseHoldings(any()))
                .thenReturn(
                        List.of(
                                new KiwoomTradeService.Holding(
                                        "005930", "삼성전자", 10, 10, 70_000, 60_000, -14.3)));

        KiwoomRiskManagerService.RiskScanResult result = service.runRiskScan("MANUAL");

        assertEquals(0, result.proposalCount());
        assertEquals(1, result.holdingCount());
        assertTrue(result.message().contains("단일 청산 관리자"));
        verify(state).recordDailyLossCheck(anyLong(), anyLong(), anyDouble());
    }

    @Test
    void disabledLoopOnlyPerformsDailyLossCheck() {
        settings.setRiskLoopEnabled(false);

        KiwoomRiskManagerService.RiskScanResult result = service.runRiskScan("MANUAL");

        assertEquals(0, result.proposalCount());
        verify(state).recordDailyLossCheck(anyLong(), anyLong(), anyDouble());
        verify(trade, never()).parseHoldings(any());
    }
}
