package com.hyunchang.webapp.service.paper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hyunchang.webapp.entity.paper.UsPaperSession;
import com.hyunchang.webapp.repository.paper.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class UsPaperStatusSnapshotTest {
    static class Transactions extends AbstractPlatformTransactionManager {
        int commits;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {}

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            fail("Snapshot unexpectedly rolled back");
        }
    }

    void assertSnapshot() {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
        assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
        assertEquals(
                TransactionDefinition.ISOLATION_REPEATABLE_READ,
                TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
    }

    UsPaperService service(Transactions transactions) {
        var sessions = mock(UsPaperSessionRepository.class);
        var fills = mock(UsPaperFillRepository.class);
        var equities = mock(UsPaperEquityRepository.class);
        var events = mock(UsPaperEventRepository.class);
        var engine = mock(UsPaperEngineService.class);
        var feed = mock(UsPaperMarketFeed.class);
        var s = new UsPaperSession();
        s.setId(1L);
        s.setInitialCashUsd(new BigDecimal("10000"));
        s.setCashUsd(s.getInitialCashUsd());
        s.setEquityUsd(s.getInitialCashUsd());
        s.setPeakEquityUsd(s.getInitialCashUsd());
        s.setCreatedAt(Instant.parse("2026-11-18T15:00:00Z"));
        when(engine.create(any())).thenReturn(1L);
        when(sessions.findById(1L)).thenReturn(Optional.of(s));
        when(sessions.findTop100ByOrderByIdDesc())
                .thenAnswer(
                        i -> {
                            assertSnapshot();
                            return List.of(s);
                        });
        when(fills.findTop100BySessionIdOrderByIdDesc(1L))
                .thenAnswer(
                        i -> {
                            assertSnapshot();
                            return List.of();
                        });
        when(equities.findTop100BySessionIdOrderByIdDesc(1L))
                .thenAnswer(
                        i -> {
                            assertSnapshot();
                            return List.of();
                        });
        when(events.findTop100BySessionIdOrderByIdDesc(1L))
                .thenAnswer(
                        i -> {
                            assertSnapshot();
                            return List.of();
                        });
        return new UsPaperService(
                sessions,
                fills,
                equities,
                events,
                engine,
                feed,
                new UsPaperMarketService(),
                Clock.fixed(s.getCreatedAt(), ZoneOffset.UTC),
                false,
                transactions);
    }

    @Test
    void commandResponseSelfCallsAlsoReadOneTransactionSnapshot() {
        var transactions = new Transactions();
        var service = service(transactions);
        assertEquals(1L, service.create(new BigDecimal("10000")).id());
        assertEquals(1L, service.start(1L).id());
        assertEquals(1L, service.stop(1L).id());
        assertEquals(1L, service.poll(1L).id());
        assertEquals(4, transactions.commits);
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Test
    void getAndListReadHistoryEventsAndBookUnderRepeatableRead() {
        var transactions = new Transactions();
        var service = service(transactions);
        assertEquals(1L, service.get(1L).id());
        assertEquals(1, service.list().size());
        assertEquals(2, transactions.commits);
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }
}
