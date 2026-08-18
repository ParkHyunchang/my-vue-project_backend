package com.hyunchang.webapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.entity.KiwoomUsStrategyRun;
import com.hyunchang.webapp.entity.KiwoomUsStrategySettings;
import com.hyunchang.webapp.repository.KiwoomUsAccountHoldingRepository;
import com.hyunchang.webapp.repository.KiwoomUsStrategyRunRepository;
import com.hyunchang.webapp.repository.KiwoomUsTradeProposalRepository;
import com.hyunchang.webapp.service.kiwoom.KiwoomUsAutoTradeState;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

class KiwoomUsAutoTradeServiceTest {

    @Test
    void decisionResultIsAlwaysWrittenToTheAuditLog() {
        KiwoomUsStrategyRunRepository runs = mock(KiwoomUsStrategyRunRepository.class);
        KiwoomUsAuditService audit = mock(KiwoomUsAuditService.class);
        KiwoomUsAutoTradeService service = service(mock(KiwoomUsTradeService.class), runs, audit);
        KiwoomUsStrategyRun run = new KiwoomUsStrategyRun();
        run.setTriggeredBy("SCHEDULE");

        ReflectionTestUtils.invokeMethod(
                service, "finishRun", run, "NO_CANDIDATE", "후보가 없습니다.", 0, null);

        verify(runs).save(run);
        verify(audit)
                .log(
                        eq("DECISION_RESULT"),
                        isNull(),
                        contains("[SCHEDULE][NO_CANDIDATE] 후보가 없습니다. 후보=0개"));
    }

    @Test
    void accountSummaryReturnsAnUnavailableSnapshotDuringSettlementWindow() {
        KiwoomUsTradeService trade = mock(KiwoomUsTradeService.class);
        when(trade.getDepositDetail())
                .thenReturn(Mono.error(new IllegalStateException("572070: 수도결제중입니다")));
        KiwoomUsAutoTradeService service =
                service(
                        trade,
                        mock(KiwoomUsStrategyRunRepository.class),
                        mock(KiwoomUsAuditService.class));

        KiwoomUsAutoTradeService.AccountSnapshot snapshot = service.accountSummary();

        assertFalse(snapshot.fresh());
        assertNull(snapshot.capturedAt());
        assertTrue(snapshot.notice().contains("수도결제"));
    }

    @Test
    void candidateScreeningReportsRemainingAndRejectedCountsForEveryStage() {
        KiwoomUsAccountHoldingRepository holdings = mock(KiwoomUsAccountHoldingRepository.class);
        KiwoomUsTradeProposalRepository proposals = mock(KiwoomUsTradeProposalRepository.class);
        KiwoomUsIndexUniverseService indexUniverse = mock(KiwoomUsIndexUniverseService.class);
        KiwoomUsTradeService trade = mock(KiwoomUsTradeService.class);
        KiwoomUsAuditService audit = mock(KiwoomUsAuditService.class);
        when(holdings.findByActiveTrueOrderByIdAsc()).thenReturn(List.of());
        when(indexUniverse.isEligible("PASS")).thenReturn(true);
        when(indexUniverse.isEligible("EXPENSIVE")).thenReturn(true);
        when(indexUniverse.isEligible("MOMENTUM")).thenReturn(true);
        when(indexUniverse.membershipLabel("PASS")).thenReturn("S&P 500");
        when(trade.getOrderBook(anyString(), anyString()))
                .thenAnswer(
                        invocation -> {
                            String symbol = invocation.getArgument(1);
                            BigDecimal ask =
                                    "EXPENSIVE".equals(symbol)
                                            ? new BigDecimal("3.00")
                                            : new BigDecimal("1.50");
                            return Mono.just(new KiwoomUsTradeService.OrderBookQuote(ask, ask));
                        });

        KiwoomUsAutoTradeService service =
                new KiwoomUsAutoTradeService(
                        new KiwoomProperties(),
                        trade,
                        mock(KiwoomUsStrategySettingsService.class),
                        mock(KiwoomUsFundamentalService.class),
                        indexUniverse,
                        mock(KiwoomUsAutoTradeState.class),
                        holdings,
                        proposals,
                        mock(KiwoomUsStrategyRunRepository.class),
                        audit,
                        mock(KiwoomUsEventService.class));
        KiwoomUsStrategySettings settings = new KiwoomUsStrategySettings();
        settings.setFundamentalFilterEnabled(false);
        KiwoomUsAutoTradeService.AccountSnapshot account =
                new KiwoomUsAutoTradeService.AccountSnapshot(
                        null,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        new BigDecimal("2.00"),
                        0,
                        0,
                        null,
                        true,
                        "",
                        LocalDateTime.now());
        List<KiwoomUsTradeService.RankedStock> ranked =
                List.of(
                        ranked("PASS", "1.50", 2, 150, 100),
                        ranked("EXPENSIVE", "3.00", 2, 150, 100),
                        ranked("MOMENTUM", "1.50", 9, 150, 100),
                        ranked("NOT_INDEX", "1.50", 2, 150, 100));

        KiwoomUsAutoTradeService.CandidateScreeningResult result =
                service.filterCandidates(ranked, settings, account, 1.0);

        assertEquals(1, result.candidates().size());
        assertEquals(4, result.stats().inputCount());
        assertEquals(4, result.stats().liquidCount());
        assertEquals(3, result.stats().indexCount());
        assertEquals(2, result.stats().momentumCount());
        assertEquals(2, result.stats().volumeCount());
        assertEquals(2, result.stats().fundamentalCount());
        assertEquals(2, result.stats().spreadCount());
        assertEquals(1, result.stats().affordableCount());
        assertEquals(1, result.stats().capacityCount());
        assertTrue(result.stats().auditMessage().contains("주문가능가격=1(탈락 1)(한도=$2.00)"));
        verify(audit)
                .log(
                        eq("CANDIDATE_REJECTED"),
                        isNull(),
                        contains("종목=NOT_INDEX(NOT_INDEX), 단계=주요지수"));
        verify(audit)
                .log(eq("CANDIDATE_REJECTED"), isNull(), contains("종목=MOMENTUM(MOMENTUM), 단계=등락률"));
        verify(audit)
                .log(
                        eq("CANDIDATE_REJECTED"),
                        isNull(),
                        contains("종목=EXPENSIVE(EXPENSIVE), 단계=주문가능가격"));
    }

    @Test
    void missingFundamentalsAreWrittenAsDataMissing() {
        KiwoomUsAccountHoldingRepository holdings = mock(KiwoomUsAccountHoldingRepository.class);
        KiwoomUsTradeProposalRepository proposals = mock(KiwoomUsTradeProposalRepository.class);
        KiwoomUsIndexUniverseService indexUniverse = mock(KiwoomUsIndexUniverseService.class);
        KiwoomUsFundamentalService fundamentals = mock(KiwoomUsFundamentalService.class);
        KiwoomUsAuditService audit = mock(KiwoomUsAuditService.class);
        when(holdings.findByActiveTrueOrderByIdAsc()).thenReturn(List.of());
        when(indexUniverse.isEligible("MISSING")).thenReturn(true);
        when(fundamentals.find("MISSING")).thenReturn(Optional.empty());

        KiwoomUsAutoTradeService service =
                new KiwoomUsAutoTradeService(
                        new KiwoomProperties(),
                        mock(KiwoomUsTradeService.class),
                        mock(KiwoomUsStrategySettingsService.class),
                        fundamentals,
                        indexUniverse,
                        mock(KiwoomUsAutoTradeState.class),
                        holdings,
                        proposals,
                        mock(KiwoomUsStrategyRunRepository.class),
                        audit,
                        mock(KiwoomUsEventService.class));
        KiwoomUsStrategySettings settings = new KiwoomUsStrategySettings();
        KiwoomUsAutoTradeService.AccountSnapshot account =
                new KiwoomUsAutoTradeService.AccountSnapshot(
                        null,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        new BigDecimal("100.00"),
                        0,
                        0,
                        null,
                        true,
                        "",
                        LocalDateTime.now());

        var result =
                service.filterCandidates(
                        List.of(ranked("MISSING", "10.00", 3, 150, 100)), settings, account, 1.0);

        assertTrue(result.candidates().isEmpty());
        verify(audit)
                .log(eq("DATA_MISSING"), isNull(), contains("종목=MISSING(MISSING), 데이터=PER·ROE"));
    }

    private KiwoomUsTradeService.RankedStock ranked(
            String symbol, String price, double changePercent, long volume, long previousVolume) {
        return new KiwoomUsTradeService.RankedStock(
                1,
                "ND",
                symbol,
                symbol,
                new BigDecimal(price),
                changePercent,
                volume,
                previousVolume,
                new BigDecimal("1000"));
    }

    private KiwoomUsAutoTradeService service(
            KiwoomUsTradeService trade,
            KiwoomUsStrategyRunRepository runs,
            KiwoomUsAuditService audit) {
        return new KiwoomUsAutoTradeService(
                new KiwoomProperties(),
                trade,
                mock(KiwoomUsStrategySettingsService.class),
                mock(KiwoomUsFundamentalService.class),
                mock(KiwoomUsIndexUniverseService.class),
                mock(KiwoomUsAutoTradeState.class),
                mock(KiwoomUsAccountHoldingRepository.class),
                mock(KiwoomUsTradeProposalRepository.class),
                runs,
                audit,
                mock(KiwoomUsEventService.class));
    }
}
