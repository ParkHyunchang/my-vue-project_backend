package com.hyunchang.webapp.service.paper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hyunchang.webapp.entity.paper.*;
import com.hyunchang.webapp.repository.paper.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UsPaperEngineServiceTest {
    final UsPaperSessionRepository sessions = mock(UsPaperSessionRepository.class);
    final UsPaperFillRepository fills = mock(UsPaperFillRepository.class);
    final UsPaperEquityRepository equities = mock(UsPaperEquityRepository.class);
    final UsPaperEventRepository events = mock(UsPaperEventRepository.class);
    final UsPaperMarketService market = new UsPaperMarketService();
    final Clock clock = mock(Clock.class);
    final List<UsPaperFill> ledger = new ArrayList<>();
    UsPaperSession account;
    UsPaperEngineService engine;
    Instant now = Instant.parse("2026-11-18T15:00:00Z");

    @BeforeEach
    void setup() {
        when(clock.instant()).thenAnswer(i -> now);
        when(sessions.saveAndFlush(any()))
                .thenAnswer(
                        i -> {
                            account = i.getArgument(0);
                            account.setId(1L);
                            return account;
                        });
        when(sessions.lockById(1L)).thenAnswer(i -> Optional.of(account));
        when(fills.save(any()))
                .thenAnswer(
                        i -> {
                            UsPaperFill f = i.getArgument(0);
                            ledger.add(f);
                            return f;
                        });
        engine = new UsPaperEngineService(sessions, fills, equities, events, market, clock);
        engine.create(new BigDecimal("10000"));
        engine.start(1L);
    }

    static List<UsPaperMarketFeed.DailyBar> bars(UsPaperMarketService market, Instant now) {
        var result = new ArrayList<UsPaperMarketFeed.DailyBar>();
        LocalDate day = market.previousSession(now);
        for (int i = 0; i < 200; i++) {
            result.add(new UsPaperMarketFeed.DailyBar(day, new BigDecimal(i == 0 ? "95" : "90")));
            day = market.previousSession(day);
        }
        Collections.reverse(result);
        return result;
    }

    UsPaperMarketFeed.MarketSnapshot quote(String price, Instant time) {
        return new UsPaperMarketFeed.MarketSnapshot(
                new BigDecimal(price), time, bars(market, now), "TEST");
    }

    @Test
    void feesCashAndWholeSharesAreAtomicAndDuplicateQuoteDoesNotFill() {
        engine.tick(1L, quote("100", now));
        assertEquals(79, account.getQuantity());
        assertEquals(1, ledger.size());
        BigDecimal debit =
                ledger.getFirst()
                        .getPriceUsd()
                        .multiply(BigDecimal.valueOf(79))
                        .add(ledger.getFirst().getFeeUsd());
        assertTrue(debit.compareTo(new BigDecimal("9900")) <= 0);
        assertEquals(0, account.getCashUsd().compareTo(new BigDecimal("10000").subtract(debit)));
        assertNotNull(account.getBenchmarkBase());
        engine.tick(1L, quote("100", now));
        assertEquals(1, ledger.size());
        now = now.plusSeconds(60);
        engine.tick(1L, quote("101", now));
        assertEquals(1, ledger.size());
        assertEquals("WAIT_NEXT_SIGNAL", account.getReason());
    }

    @Test
    void staleFutureAndClosedDataNeverProduceFills() {
        engine.tick(1L, quote("100", now.minusSeconds(301)));
        assertEquals("STALE_QUOTE", account.getReason());
        engine.tick(1L, quote("100", now.plusSeconds(31)));
        assertEquals("STALE_QUOTE", account.getReason());
        now = Instant.parse("2026-11-21T15:00:00Z");
        engine.tick(1L, null);
        assertEquals("MARKET_CLOSED", account.getReason());
        assertTrue(ledger.isEmpty());
        assertNull(account.getLastProcessedQuote());
    }

    @Test
    void missingDailySessionBlocksEntryButNotRiskExit() {
        var broken = new ArrayList<>(bars(market, now));
        broken.remove(99);
        engine.tick(
                1L,
                new UsPaperMarketFeed.MarketSnapshot(new BigDecimal("100"), now, broken, "TEST"));
        assertTrue(ledger.isEmpty());
        assertEquals("HISTORY_NOT_READY", account.getReason());
        now = now.plusSeconds(60);
        engine.tick(1L, quote("100", now));
        now = now.plusSeconds(60);
        engine.tick(
                1L,
                new UsPaperMarketFeed.MarketSnapshot(new BigDecimal("80"), now, List.of(), "TEST"));
        assertTrue(account.isHardLatched());
        assertEquals(0, account.getQuantity());
        assertEquals(2, ledger.size());
    }

    @Test
    void hardLatchOverridesBuyAndSurvivesStopResumeAttempt() {
        engine.tick(1L, quote("100", now));
        now = now.plusSeconds(60);
        engine.tick(1L, quote("85", now));
        assertEquals("HARD", account.getState());
        assertEquals(0, account.getQuantity());
        assertTrue(account.getMaxDrawdownPercent().compareTo(BigDecimal.TEN) > 0);
        BigDecimal peak = account.getPeakEquityUsd();
        engine.stop(1L);
        assertThrows(IllegalStateException.class, () -> engine.start(1L));
        now = now.plusSeconds(60);
        engine.tick(1L, quote("200", now));
        assertEquals(2, ledger.size());
        assertEquals(peak, account.getPeakEquityUsd());
    }

    @Test
    void stopPausesAllExecutionAndResumePreservesBookAndPeak() {
        engine.tick(1L, quote("100", now));
        engine.stop(1L);
        BigDecimal peak = account.getPeakEquityUsd(), cash = account.getCashUsd();
        now = now.plusSeconds(60);
        engine.tick(1L, quote("80", now));
        assertEquals(1, ledger.size());
        assertEquals(cash, account.getCashUsd());
        engine.start(1L);
        engine.tick(1L, quote("80", now));
        assertTrue(account.isHardLatched());
        assertEquals(peak, account.getPeakEquityUsd());
    }

    @Test
    void priceRoundingCannotCreateOverBudgetBuy() {
        account.setCashUsd(new BigDecimal("123.45678901"));
        account.setInitialCashUsd(account.getCashUsd());
        account.setEquityUsd(account.getCashUsd());
        account.setPeakEquityUsd(account.getCashUsd());
        engine.tick(1L, quote("0.33333333333", now));
        assertTrue(account.getCashUsd().signum() >= 0);
        assertTrue(
                ledger.getFirst()
                                .getPriceUsd()
                                .multiply(BigDecimal.valueOf(ledger.getFirst().getQuantity()))
                                .add(ledger.getFirst().getFeeUsd())
                                .compareTo(
                                        new BigDecimal("123.45678901")
                                                .multiply(new BigDecimal("0.99")))
                        <= 0);
    }

    @Test
    void invalidInitialMoneyRejectedWithoutReplacingExistingBook() {
        assertThrows(IllegalArgumentException.class, () -> engine.create(new BigDecimal("0")));
        assertThrows(
                IllegalArgumentException.class, () -> engine.create(new BigDecimal("100.001")));
        assertEquals(new BigDecimal("10000"), account.getInitialCashUsd());
    }
}
