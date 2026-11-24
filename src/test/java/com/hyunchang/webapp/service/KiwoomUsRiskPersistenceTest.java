package com.hyunchang.webapp.service;

import static org.junit.jupiter.api.Assertions.*;

import com.hyunchang.webapp.entity.*;
import com.hyunchang.webapp.repository.*;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

class KiwoomUsRiskPersistenceTest {
    @Test
    void persistedLatchFlowLedgerAndSubmissionSurviveSessionRestart() {
        var config =
                new Configuration()
                        .addAnnotatedClass(KiwoomUsAccountRisk.class)
                        .addAnnotatedClass(KiwoomUsCapitalFlow.class)
                        .addAnnotatedClass(KiwoomUsTradeProposal.class)
                        .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
                        .setProperty(
                                "hibernate.connection.url", "jdbc:h2:mem:risk_" + UUID.randomUUID())
                        .setProperty("hibernate.hbm2ddl.auto", "create-drop");
        try (var factory = config.buildSessionFactory()) {
            Long proposalId;
            try (var session = factory.openSession()) {
                var tx = session.beginTransaction();
                var repositories = new JpaRepositoryFactory(session);
                var service =
                        new KiwoomUsAccountRiskService(
                                repositories.getRepository(KiwoomUsAccountRiskRepository.class),
                                repositories.getRepository(KiwoomUsCapitalFlowRepository.class));
                service.activate(new BigDecimal("1000"), true, true);
                service.requireFlowReview(true);
                service.recordFlow("bank-1", new BigDecimal("1000"), true, new BigDecimal("1900"));
                var proposal = new KiwoomUsTradeProposal();
                proposal.setAction(KiwoomUsTradeProposal.Action.BUY);
                proposal.setExchange("ND");
                proposal.setSymbol("TEST");
                proposal.setQuantity(2);
                proposal.submitting();
                session.persist(proposal);
                tx.commit();
                proposalId = proposal.getId();
            }
            try (var session = factory.openSession()) {
                var repositories = new JpaRepositoryFactory(session);
                var service =
                        new KiwoomUsAccountRiskService(
                                repositories.getRepository(KiwoomUsAccountRiskRepository.class),
                                repositories.getRepository(KiwoomUsCapitalFlowRepository.class));
                assertTrue(service.status().triggered());
                assertTrue(service.status().liquidationPending());
                assertEquals(0, service.status().drawdownPercent().compareTo(BigDecimal.TEN));
                assertThrows(IllegalStateException.class, service::requireBuyAllowed);
                assertTrue(
                        repositories
                                .getRepository(KiwoomUsCapitalFlowRepository.class)
                                .existsById("bank-1"));
                var saved = session.find(KiwoomUsTradeProposal.class, proposalId);
                assertEquals(KiwoomUsTradeProposal.Status.SUBMITTING, saved.getStatus());
                assertNotNull(saved.getOrderedAt());
            }
        }
    }
}
