package com.hyunchang.webapp.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.entity.KiwoomUsStrategySettings;
import com.hyunchang.webapp.repository.KiwoomUsStrategySettingsRepository;
import com.hyunchang.webapp.service.*;
import com.hyunchang.webapp.service.kiwoom.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class KiwoomTradingControlsTest {
    private final KiwoomProperties properties = new KiwoomProperties();
    private final KiwoomUsAutoTradeService usService = mock(KiwoomUsAutoTradeService.class);
    private final KiwoomUsAutoTradeState usState = mock(KiwoomUsAutoTradeState.class);
    private final KiwoomUsAutoTradeController us =
            new KiwoomUsAutoTradeController(
                    properties,
                    mock(KiwoomAuthService.class),
                    usState,
                    usService,
                    mock(KiwoomUsStrategySettingsService.class),
                    mock(KiwoomUsAuditService.class),
                    mock(KiwoomUsEventService.class),
                    mock(KiwoomUsIndexUniverseService.class));

    private void enable() {
        properties.setAppKey("test");
        properties.setSecretKey("test");
        properties.setAccountNo("test");
        properties.setTradeEnabled(true);
        properties.getUs().setTradeEnabled(true);
    }

    @Test
    void usStopDoesNotRequireBrokerQueriesOrCancelExistingOrders() {
        assertEquals(
                200,
                us.control(new KiwoomUsAutoTradeController.ControlRequest(false))
                        .getStatusCode()
                        .value());
        verify(usState).setAutoTrading(false);
        verifyNoInteractions(usService);
    }

    @Test
    void usStartChecksUnknownOrdersAndUsdFundingBeforeEnabling() {
        enable();
        when(usService.refreshUsdCash())
                .thenReturn(
                        new KiwoomUsTradeService.UsdCash(
                                BigDecimal.TEN, "test", BigDecimal.ZERO, true, ""));
        us.control(new KiwoomUsAutoTradeController.ControlRequest(true));
        var order = inOrder(usService, usState);
        order.verify(usService).validateRestart();
        order.verify(usService).refreshUsdCash();
        order.verify(usState).setAutoTrading(true);
    }

    @Test
    void usStartRejectsDisabledStrategyOrUnresolvedOrders() {
        enable();
        properties.getUs().setStrategyEnabled(false);
        assertEquals(
                409,
                us.control(new KiwoomUsAutoTradeController.ControlRequest(true))
                        .getStatusCode()
                        .value());
        verify(usState, never()).setAutoTrading(true);
        properties.getUs().setStrategyEnabled(true);
        doThrow(new IllegalStateException("UNKNOWN")).when(usService).validateRestart();
        assertThrows(
                IllegalStateException.class,
                () -> us.control(new KiwoomUsAutoTradeController.ControlRequest(true)));
        verify(usService, never()).refreshUsdCash();
    }

    @Test
    void usFundingBlockNeverEnablesTrading() {
        enable();
        when(usService.refreshUsdCash())
                .thenReturn(
                        new KiwoomUsTradeService.UsdCash(
                                BigDecimal.TEN, "test", BigDecimal.ONE, false, "원화주문 차단"));
        assertEquals(
                409,
                us.control(new KiwoomUsAutoTradeController.ControlRequest(true))
                        .getStatusCode()
                        .value());
        verify(usState, never()).setAutoTrading(true);
    }

    @Test
    void usManualSyncReturnsPartialWarningButStillRefreshesHoldingsWithoutDeciding() {
        doThrow(new IllegalStateException("broker outage")).when(usService).reconcileOrders();
        var snapshot = mock(KiwoomUsAutoTradeService.AccountSnapshot.class);
        when(usService.accountSummary()).thenReturn(snapshot);
        var result = us.sync();
        assertEquals(false, result.get("success"));
        assertFalse(((List<?>) result.get("warnings")).isEmpty());
        assertSame(snapshot, result.get("snapshot"));
        var order = inOrder(usService);
        order.verify(usService).reconcileOrders();
        order.verify(usService).syncHoldings();
        order.verify(usService).accountSummary();
        verify(usService, never()).decide(anyString(), anyBoolean());
    }

    @Test
    void usManualSyncDoesNotReportSuccessWhenBalanceFails() {
        doThrow(new IllegalStateException("balance outage")).when(usService).syncHoldings();
        assertThrows(IllegalStateException.class, us::sync);
        verify(usService, never()).accountSummary();
    }

    @Test
    void usPreviewPassesFalseWithoutEnablingTrading() {
        us.decide(false);
        verify(usService).decide("ADMIN", false);
        verifyNoInteractions(usState);
    }

    @Test
    void trendSettingsKeepUnusedLegacyMinimumButAllowLowerMaximum() {
        var repository = mock(KiwoomUsStrategySettingsRepository.class);
        when(repository.findById(1L)).thenReturn(Optional.of(new KiwoomUsStrategySettings()));
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        var settings = new KiwoomUsStrategySettingsService(repository, properties);
        var incoming = new KiwoomUsStrategySettings();
        incoming.setSignalMode(KiwoomUsStrategySettings.SignalMode.TREND);
        incoming.setMinChangePercent(2);
        incoming.setMaxChangePercent(1);
        incoming.setRiskPerTradePercent(0.3);
        var saved = settings.save(incoming);
        assertEquals(2, saved.getMinChangePercent());
        assertEquals(1, saved.getMaxChangePercent());
        assertEquals(0.3, saved.getRiskPerTradePercent());
    }

    @Test
    void krStartEnablesExecutionAndExitLoopAndReturnsPreparationState() {
        enable();
        var state = mock(KiwoomAutoTradeState.class);
        var settings = mock(KiwoomStrategySettingsService.class);
        var exits = mock(KiwoomPositionExitService.class);
        var controller = kr(state, settings, exits);
        var result = controller.control(new KiwoomAutoTradeController.ControlRequest(true));
        verify(settings).activateFullAutomation();
        verify(state).setAutoTrading(true);
        verify(exits).resumeExitManagement();
        assertEquals(false, result.getBody().get("exitManagementReady"));
    }

    @Test
    void krStopDisablesTradingBeforeCancelingAndExposesCancellationFailures() {
        var state = mock(KiwoomAutoTradeState.class);
        var settings = mock(KiwoomStrategySettingsService.class);
        var exits = mock(KiwoomPositionExitService.class);
        when(exits.pauseExitManagement())
                .thenReturn(new KiwoomPositionExitService.PauseResult(2, 1));
        var result =
                kr(state, settings, exits)
                        .control(new KiwoomAutoTradeController.ControlRequest(false));
        var order = inOrder(state, exits);
        order.verify(state).setAutoTrading(false);
        order.verify(exits).pauseExitManagement();
        assertEquals(1, result.getBody().get("orderCancellationFailed"));
        verifyNoInteractions(settings);
    }

    private KiwoomAutoTradeController kr(
            KiwoomAutoTradeState state,
            KiwoomStrategySettingsService settings,
            KiwoomPositionExitService exits) {
        return new KiwoomAutoTradeController(
                properties,
                mock(KiwoomAuthService.class),
                mock(KiwoomTradeService.class),
                mock(KiwoomWebsocketClient.class),
                state,
                mock(KiwoomStrategyService.class),
                settings,
                mock(KiwoomStrategyAuditService.class),
                exits,
                mock(KiwoomAccountHoldingSyncService.class));
    }
}
