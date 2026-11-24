package com.hyunchang.webapp.service.paper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hyunchang.webapp.entity.paper.*;
import com.hyunchang.webapp.repository.paper.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

class UsPaperPersistenceTest {
    private static final Instant NOW = Instant.parse("2026-11-18T15:00:00Z");

    SessionFactory factory() {
        return new Configuration()
                .addAnnotatedClass(UsPaperSession.class)
                .addAnnotatedClass(UsPaperFill.class)
                .addAnnotatedClass(UsPaperEquity.class)
                .addAnnotatedClass(UsPaperEvent.class)
                .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:paper_" + UUID.randomUUID())
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .buildSessionFactory();
    }

    UsPaperEngineService engine(Session session, Instant now, UsPaperEquityRepository override) {
        var r = new JpaRepositoryFactory(session);
        return new UsPaperEngineService(
                r.getRepository(UsPaperSessionRepository.class),
                r.getRepository(UsPaperFillRepository.class),
                override == null ? r.getRepository(UsPaperEquityRepository.class) : override,
                r.getRepository(UsPaperEventRepository.class),
                new UsPaperMarketService(),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    UsPaperMarketFeed.MarketSnapshot quote(Instant now, String price) {
        return new UsPaperMarketFeed.MarketSnapshot(
                new BigDecimal(price),
                now,
                UsPaperEngineServiceTest.bars(new UsPaperMarketService(), now),
                "TEST");
    }

    @Test
    void ledgerHighWaterDedupAndHardSurviveNewPersistenceContext() {
        try (var factory = factory()) {
            Long id;
            try (var s = factory.openSession()) {
                var tx = s.beginTransaction();
                var e = engine(s, NOW, null);
                id = e.create(new BigDecimal("10000"));
                e.start(id);
                e.tick(id, quote(NOW, "100"));
                tx.commit();
            }
            try (var s = factory.openSession()) {
                var tx = s.beginTransaction();
                var e = engine(s, NOW, null);
                e.tick(id, quote(NOW, "100"));
                assertEquals(
                        1L,
                        s.createQuery("select count(f) from UsPaperFill f", Long.class)
                                .getSingleResult());
                var book = s.find(UsPaperSession.class, id);
                assertEquals(79, book.getQuantity());
                assertEquals(NOW, book.getLastProcessedQuote());
                tx.commit();
            }
            try (var s = factory.openSession()) {
                var tx = s.beginTransaction();
                engine(s, NOW.plusSeconds(60), null).tick(id, quote(NOW.plusSeconds(60), "80"));
                tx.commit();
            }
            try (var s = factory.openSession()) {
                var tx = s.beginTransaction();
                var book = s.find(UsPaperSession.class, id);
                assertTrue(book.isHardLatched());
                assertEquals("HARD", book.getState());
                assertEquals(0, book.getQuantity());
                assertEquals(0, new BigDecimal("10000").compareTo(book.getPeakEquityUsd()));
                assertThrows(
                        IllegalStateException.class,
                        () -> engine(s, NOW.plusSeconds(120), null).start(id));
                assertEquals(
                        2L,
                        s.createQuery("select count(f) from UsPaperFill f", Long.class)
                                .getSingleResult());
                tx.rollback();
            }
        }
    }

    @Test
    void crashBeforeCommitDoesNotPersistFillCashOrDeduplication() {
        try (var factory = factory()) {
            Long id;
            try (var s = factory.openSession()) {
                var tx = s.beginTransaction();
                var e = engine(s, NOW, null);
                id = e.create(new BigDecimal("10000"));
                e.start(id);
                tx.commit();
            }
            try (var s = factory.openSession()) {
                var tx = s.beginTransaction();
                var failed = mock(UsPaperEquityRepository.class);
                when(failed.save(any()))
                        .thenThrow(new IllegalStateException("simulated storage failure"));
                assertThrows(
                        IllegalStateException.class,
                        () -> engine(s, NOW, failed).tick(id, quote(NOW, "100")));
                tx.rollback();
            }
            try (var s = factory.openSession()) {
                var tx = s.beginTransaction();
                var book = s.find(UsPaperSession.class, id);
                assertEquals(0, book.getQuantity());
                assertNull(book.getLastProcessedQuote());
                assertEquals(0, book.getCashUsd().compareTo(new BigDecimal("10000")));
                assertEquals(
                        0L,
                        s.createQuery("select count(f) from UsPaperFill f", Long.class)
                                .getSingleResult());
                engine(s, NOW, null).tick(id, quote(NOW, "100"));
                tx.commit();
            }
            try (var s = factory.openSession()) {
                assertEquals(
                        1L,
                        s.createQuery("select count(f) from UsPaperFill f", Long.class)
                                .getSingleResult());
            }
        }
    }
}
