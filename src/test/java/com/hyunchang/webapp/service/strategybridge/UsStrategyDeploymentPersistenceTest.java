package com.hyunchang.webapp.service.strategybridge;

import static org.junit.jupiter.api.Assertions.*;

import com.hyunchang.webapp.entity.UsStrategyDeployment;
import com.hyunchang.webapp.entity.UsStrategyDeploymentAudit;
import com.hyunchang.webapp.repository.UsStrategyDeploymentRepository;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

/** Actual entity and repository persistence, only a UUID-scoped in-memory H2 database. */
class UsStrategyDeploymentPersistenceTest {
    private SessionFactory factory() {
        return new Configuration()
                .addAnnotatedClass(UsStrategyDeployment.class)
                .addAnnotatedClass(UsStrategyDeploymentAudit.class)
                .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
                .setProperty(
                        "hibernate.connection.url",
                        "jdbc:h2:mem:bridge_" + UUID.randomUUID() + ";LOCK_TIMEOUT=100")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .buildSessionFactory();
    }

    private void seed(SessionFactory factory) {
        try (var session = factory.openSession()) {
            var tx = session.beginTransaction();
            var row = new UsStrategyDeployment();
            row.setAccountHash("a".repeat(64));
            row.setDeploymentId("approved-test-only");
            row.setManaged(true);
            row.setActive(true);
            row.setActivePayload("{\"candidateId\":\"rotation1:recovery_vol\"}");
            row.setExecutionState("{\"basket\":[\"SPY\"],\"anchor\":0.4}");
            row.setClaimedSignalDate("2026-11-20");
            row.setLastExecution("CLAIMED: interrupted test only");
            session.persist(row);
            session.persist(
                    new UsStrategyDeploymentAudit(
                            "2026-11-23T02:00:00Z",
                            "test-admin",
                            row.getAccountHash(),
                            row.getDeploymentId(),
                            "APPROVED",
                            "test only"));
            tx.commit();
        }
    }

    @Test
    void committedApprovalClaimAndAuditSurviveASeparateSession() {
        try (var factory = factory()) {
            seed(factory);
            try (var session = factory.openSession()) {
                var tx = session.beginTransaction();
                var repository =
                        new JpaRepositoryFactory(session)
                                .getRepository(UsStrategyDeploymentRepository.class);
                var row = repository.findForUpdate().orElseThrow();
                assertTrue(row.isManaged());
                assertTrue(row.isActive());
                assertNotNull(row.getRevision());
                assertEquals("2026-11-20", row.getClaimedSignalDate());
                assertTrue(row.getExecutionState().contains("0.4"));
                assertTrue(row.getLastExecution().startsWith("CLAIMED:"));
                assertEquals(
                        1L,
                        session.createQuery(
                                        "select count(a) from UsStrategyDeploymentAudit a",
                                        Long.class)
                                .getSingleResult());
                long oldRevision = row.getRevision();
                row.setActive(false);
                repository.saveAndFlush(row);
                assertTrue(row.getRevision() > oldRevision);
                tx.commit();
            }
            try (var session = factory.openSession()) {
                var row = session.find(UsStrategyDeployment.class, 1L);
                assertFalse(row.isActive());
                assertTrue(row.isManaged());
                assertEquals("2026-11-20", row.getClaimedSignalDate());
                assertTrue(row.getExecutionState().contains("0.4"));
            }
        }
    }

    @Test
    void staleConcurrentVersionCannotOverwriteNewApprovalState() {
        try (var factory = factory()) {
            seed(factory);
            try (var first = factory.openSession();
                    var second = factory.openSession()) {
                var tx1 = first.beginTransaction();
                var tx2 = second.beginTransaction();
                var row1 = first.find(UsStrategyDeployment.class, 1L);
                var row2 = second.find(UsStrategyDeployment.class, 1L);
                row1.setLastExecution("COMPLETED: first writer");
                tx1.commit();
                row2.setLastExecution("COMPLETED: stale writer");
                assertThrows(RuntimeException.class, tx2::commit);
                if (tx2.isActive()) tx2.rollback();
            }
            try (var session = factory.openSession()) {
                assertEquals(
                        "COMPLETED: first writer",
                        session.find(UsStrategyDeployment.class, 1L).getLastExecution());
            }
        }
    }

    @Test
    void repositoryWriteLockExcludesASecondTransaction() {
        try (var factory = factory()) {
            seed(factory);
            try (var first = factory.openSession();
                    var second = factory.openSession()) {
                var tx1 = first.beginTransaction();
                var tx2 = second.beginTransaction();
                var repo1 =
                        new JpaRepositoryFactory(first)
                                .getRepository(UsStrategyDeploymentRepository.class);
                var repo2 =
                        new JpaRepositoryFactory(second)
                                .getRepository(UsStrategyDeploymentRepository.class);
                assertTrue(repo1.findForUpdate().isPresent());
                assertThrows(RuntimeException.class, repo2::findForUpdate);
                if (tx2.isActive()) tx2.rollback();
                tx1.rollback();
            }
        }
    }
}
