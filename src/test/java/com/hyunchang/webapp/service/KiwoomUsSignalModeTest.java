package com.hyunchang.webapp.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.entity.*;
import com.hyunchang.webapp.entity.KiwoomUsStrategySettings.SignalMode;
import com.hyunchang.webapp.repository.*;
import com.hyunchang.webapp.service.kiwoom.KiwoomUsAutoTradeState;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class KiwoomUsSignalModeTest {
    private final KiwoomUsTradeProposalRepository proposals =
            mock(KiwoomUsTradeProposalRepository.class);
    private final KiwoomUsTechnicalSignalService signals =
            mock(KiwoomUsTechnicalSignalService.class);
    private final KiwoomUsStrategySettings settings = new KiwoomUsStrategySettings();
    private final KiwoomUsAutoTradeService service;

    KiwoomUsSignalModeTest() {
        var trade = mock(KiwoomUsTradeService.class);
        var universe = mock(KiwoomUsIndexUniverseService.class);
        when(universe.isEligible("TEST")).thenReturn(true);
        when(trade.getOrderBook("ND", "TEST"))
                .thenReturn(
                        Mono.just(
                                new KiwoomUsTradeService.OrderBookQuote(
                                        BigDecimal.TEN, BigDecimal.TEN)));
        when(signals.evaluate(anyString(), anyString(), any(), any()))
                .thenReturn(KiwoomUsTechnicalSignalService.Signal.unavailable("일봉 누락"));
        settings.setFundamentalFilterEnabled(false);
        service =
                new KiwoomUsAutoTradeService(
                        new KiwoomProperties(),
                        trade,
                        mock(KiwoomUsStrategySettingsService.class),
                        mock(KiwoomUsFundamentalService.class),
                        universe,
                        signals,
                        mock(KiwoomUsAutoTradeState.class),
                        mock(KiwoomUsAccountHoldingRepository.class),
                        proposals,
                        mock(KiwoomUsStrategyRunRepository.class),
                        mock(KiwoomUsAuditService.class),
                        mock(KiwoomUsEventService.class));
    }

    private KiwoomUsAutoTradeService.CandidateScreeningResult screen() {
        var account =
                new KiwoomUsAutoTradeService.AccountSnapshot(
                        null,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        new BigDecimal("10000"),
                        new BigDecimal("10000"),
                        new BigDecimal("1000"),
                        0,
                        0,
                        null,
                        true,
                        "",
                        LocalDateTime.now());
        var stock =
                new KiwoomUsTradeService.RankedStock(
                        1,
                        "ND",
                        "TEST",
                        "Test",
                        BigDecimal.TEN,
                        3,
                        150,
                        100,
                        new BigDecimal("100000000"));
        return service.filterCandidates(List.of(stock), settings, account, 1.0);
    }

    @Test
    void observeKeepsLegacyCandidatesButTrendFailsClosedOnMissingData() {
        assertEquals(SignalMode.OBSERVE, settings.getSignalMode());
        assertEquals(1, screen().candidates().size());
        settings.setSignalMode(SignalMode.TREND);
        var result = screen();
        assertTrue(result.candidates().isEmpty());
        assertEquals(1, result.stats().capacityCount());
        assertEquals(0, result.stats().signalCount());
        assertTrue(result.stats().auditMessage().contains("매수신호·위험예산=0(탈락 1)"));
    }

    @Test
    void legacyDoesNotRequireDailyBars() {
        settings.setSignalMode(SignalMode.LEGACY);
        assertEquals(1, screen().candidates().size());
        verifyNoInteractions(signals);
    }

    @Test
    void confirmedBuyFillImposesCooldownEvenAfterPartialCancellation() {
        when(proposals.existsBySymbolAndActionAndFilledQuantityGreaterThanAndOrderedAtAfter(
                        eq("TEST"), eq(KiwoomUsTradeProposal.Action.BUY), eq(0), any()))
                .thenReturn(true);
        assertEquals(0, screen().stats().cooldownCount());
        verifyNoInteractions(signals);
    }
}
