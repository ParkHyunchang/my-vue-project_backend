package com.hyunchang.webapp.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.service.*;
import com.hyunchang.webapp.service.kiwoom.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class KiwoomSummarySafetyTest {
    private final KiwoomTradeService trade = mock(KiwoomTradeService.class);
    private final KiwoomAutoTradeState state = mock(KiwoomAutoTradeState.class);
    private final KiwoomProposalOrderService orders = mock(KiwoomProposalOrderService.class);
    private final KiwoomAutoTradeController controller =
            new KiwoomAutoTradeController(
                    new KiwoomProperties(),
                    mock(KiwoomAuthService.class),
                    trade,
                    mock(KiwoomWebsocketClient.class),
                    state,
                    mock(KiwoomStrategyService.class),
                    mock(KiwoomStrategySettingsService.class),
                    mock(KiwoomStrategyAuditService.class),
                    mock(KiwoomPositionExitService.class),
                    mock(KiwoomAccountHoldingSyncService.class),
                    orders);

    @Test
    void summaryUsesOnlyDisplayReads() throws Exception {
        var empty = new ObjectMapper().readTree("{}");
        when(trade.getDepositForDisplay()).thenReturn(Mono.just(empty));
        when(trade.getBalanceForDisplay()).thenReturn(Mono.just(empty));
        assertNull(controller.summary().block().get("totalAsset"));
        verify(trade, never()).getDeposit();
        verify(trade, never()).getBalance();
        verifyNoInteractions(state);
    }

    @Test
    void legacyOrderCannotBypassUnknownOrEmergencyGate() {
        when(orders.submissionBlockReason()).thenReturn("대사 전 재개 불가");
        assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () ->
                        controller
                                .placeOrder(
                                        new KiwoomTradeService.OrderRequest(
                                                "BUY", "005930", 1, 100L, "LIMIT", "KRX"))
                                .block());
        verifyNoInteractions(trade);
    }
}
