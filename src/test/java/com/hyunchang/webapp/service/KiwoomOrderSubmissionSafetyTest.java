package com.hyunchang.webapp.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.entity.KiwoomTradeProposal;
import com.hyunchang.webapp.repository.KiwoomTradeProposalRepository;
import com.hyunchang.webapp.service.kiwoom.KiwoomAutoTradeState;
import com.hyunchang.webapp.service.kiwoom.KiwoomWebsocketClient;
import com.hyunchang.webapp.util.KiwoomMarketHours;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

class KiwoomOrderSubmissionSafetyTest {
    private final KiwoomTradeProposalRepository repository =
            mock(KiwoomTradeProposalRepository.class);
    private final KiwoomTradeService trade = mock(KiwoomTradeService.class);
    private final KiwoomAutoTradeState state = mock(KiwoomAutoTradeState.class);
    private final KiwoomProperties properties = new KiwoomProperties();
    private final KiwoomProposalOrderService service =
            new KiwoomProposalOrderService(
                    repository,
                    trade,
                    properties,
                    mock(KiwoomWebsocketClient.class),
                    state,
                    mock(KiwoomStrategyAuditService.class),
                    mock(KiwoomStrategySettingsService.class),
                    mock(KiwoomCandidateQualityService.class));

    private KiwoomTradeProposal draft() {
        KiwoomTradeProposal p = new KiwoomTradeProposal();
        ReflectionTestUtils.setField(p, "id", 1L);
        ReflectionTestUtils.setField(p, "status", KiwoomTradeProposal.Status.ORDER_DRAFT);
        ReflectionTestUtils.setField(p, "action", KiwoomTradeProposal.Action.SELL);
        ReflectionTestUtils.setField(p, "orderType", KiwoomTradeProposal.OrderType.MARKET);
        ReflectionTestUtils.setField(p, "stockCode", "005930");
        ReflectionTestUtils.setField(p, "quantity", 1);
        when(repository.findById(1L)).thenReturn(Optional.of(p));
        properties.setTradeEnabled(true);
        return p;
    }

    @Test
    void timeoutStopsAndPersistsUnknownRatherThanFailed() {
        KiwoomTradeProposal p = draft();
        when(trade.placeOrder(any())).thenReturn(Mono.error(new TimeoutException("lost response")));
        try (var market = mockStatic(KiwoomMarketHours.class)) {
            market.when(KiwoomMarketHours::isOpen).thenReturn(true);
            assertFalse(service.execute(1, true).success());
        }
        assertEquals(KiwoomTradeProposal.Status.ORDER_UNKNOWN, p.getStatus());
        verify(state).emergencyStop();
        verify(repository).save(p);
        verify(trade, never()).getBalance();
    }

    @Test
    void connectionLossIsAlsoUnknown() {
        KiwoomTradeProposal p = draft();
        when(trade.placeOrder(any()))
                .thenReturn(Mono.error(new java.io.IOException("connection reset")));
        try (var market = mockStatic(KiwoomMarketHours.class)) {
            market.when(KiwoomMarketHours::isOpen).thenReturn(true);
            service.execute(1, true);
        }
        assertEquals(KiwoomTradeProposal.Status.ORDER_UNKNOWN, p.getStatus());
    }

    @Test
    void explicitBrokerRejectionRemainsFailed() {
        KiwoomTradeProposal p = draft();
        when(trade.placeOrder(any()))
                .thenReturn(Mono.error(new IllegalStateException("키움 API 오류(kt10001): 거절")));
        when(trade.getBalance()).thenReturn(Mono.empty());
        try (var market = mockStatic(KiwoomMarketHours.class)) {
            market.when(KiwoomMarketHours::isOpen).thenReturn(true);
            service.execute(1, true);
        }
        assertEquals(KiwoomTradeProposal.Status.ORDER_FAILED, p.getStatus());
        verify(state, never()).emergencyStop();
    }

    @Test
    void unresolvedUnknownBlocksAnotherOrderEvenAfterEmergencyFlagReset() {
        draft();
        when(repository.findByStatusIn(List.of(KiwoomTradeProposal.Status.ORDER_UNKNOWN)))
                .thenReturn(List.of(new KiwoomTradeProposal()));
        assertFalse(service.execute(1, true).success());
        assertFalse(service.amend(1, 1, 100L).success());
        verifyNoInteractions(trade);
    }

    @Test
    void overflowCannotSaveAnEditedDraft() {
        KiwoomTradeProposal p = draft();
        assertFalse(service.updateDraft(1, 2, Long.MAX_VALUE).success());
        verify(repository, never()).save(any());
        assertEquals(1, p.getQuantity());
    }
}
