package com.hyunchang.webapp.service;

import static org.junit.jupiter.api.Assertions.*;

import com.hyunchang.webapp.dto.PortfolioAccountDtos.*;
import com.hyunchang.webapp.entity.*;
import com.hyunchang.webapp.repository.*;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.web.server.ResponseStatusException;

class PortfolioAccountServiceTest {
    @Test
    void preservesLegacyAndSeparatesOwnersAndAccountsWithDecimalHoldings() {
        Configuration config =
                new Configuration()
                        .addAnnotatedClass(User.class)
                        .addAnnotatedClass(PortfolioAccount.class)
                        .addAnnotatedClass(AccountHolding.class)
                        .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
                        .setProperty(
                                "hibernate.connection.url",
                                "jdbc:h2:mem:accounts_" + UUID.randomUUID())
                        .setProperty("hibernate.connection.username", "sa")
                        .setProperty("hibernate.connection.password", "")
                        .setProperty("hibernate.hbm2ddl.auto", "create-drop");
        try (var factory = config.buildSessionFactory();
                var session = factory.openSession()) {
            var tx = session.beginTransaction();
            var repositories = new JpaRepositoryFactory(session);
            var users = repositories.getRepository(UserRepository.class);
            var accounts = repositories.getRepository(PortfolioAccountRepository.class);
            var holdings = repositories.getRepository(AccountHoldingRepository.class);
            for (String id : java.util.List.of("owner-a", "owner-b"))
                users.saveAndFlush(
                        User.builder()
                                .userId(id)
                                .name(id)
                                .email(id + "@example.invalid")
                                .password("test-only")
                                .role("USER")
                                .build());
            var service = new PortfolioAccountService(accounts, holdings, users);
            var legacy = service.list("owner-a");
            assertEquals(4, legacy.size());
            assertEquals(0, accounts.count());
            assertEquals("/api/portfolio/holdings", legacy.get(0).holdingsPath());
            var mapped =
                    service.update(
                            "owner-a",
                            "legacy-stock",
                            new AccountRequest("SAMSUNG", "나의 장기 계좌", "LONG_TERM"));
            assertEquals("stock", mapped.legacyType());
            assertEquals("/api/portfolio/holdings", mapped.holdingsPath());
            assertEquals("UNASSIGNED", service.list("owner-b").get(0).broker());
            assertEquals(
                    0, holdings.count()); // Metadata linking does not migrate, clone or delete any
            // holding.
            var first =
                    service.create("owner-a", new AccountRequest("SAMSUNG", "삼성 미국", "LONG_TERM"));
            var second =
                    service.create("owner-a", new AccountRequest("SHINHAN", "신한 미국", "SHORT_TERM"));
            var request =
                    new HoldingRequest(
                            "STOCK",
                            "US",
                            "Synthetic ETF",
                            "DEMO",
                            new BigDecimal("1.125"),
                            new BigDecimal("123.45678901"));
            var added = service.add("owner-a", first.id(), request);
            service.add("owner-a", second.id(), request);
            session.flush();
            session.clear();
            var read = service.holdings("owner-a", first.id()).get(0);
            assertEquals(0, new BigDecimal("1.125").compareTo(read.quantity()));
            assertEquals(0, new BigDecimal("123.45678901").compareTo(read.avgPrice()));
            assertEquals("USD", read.currency());
            assertEquals("MANUAL", read.source());
            assertNotNull(read.updatedAt());
            assertThrows(
                    ResponseStatusException.class,
                    () -> service.add("owner-a", first.id(), request));
            assertThrows(
                    EntityNotFoundException.class, () -> service.holdings("owner-b", first.id()));
            assertThrows(
                    EntityNotFoundException.class,
                    () ->
                            service.update(
                                    "owner-b",
                                    first.id(),
                                    new AccountRequest("OTHER", "forbidden", "OTHER")));
            assertThrows(
                    EntityNotFoundException.class,
                    () -> service.delete("owner-a", second.id(), added.id()));
            service.updateHolding(
                    "owner-a",
                    first.id(),
                    added.id(),
                    new HoldingUpdate(new BigDecimal("0.125"), null));
            assertNull(service.holdings("owner-a", first.id()).get(0).avgPrice());
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            service.updateHolding(
                                    "owner-a",
                                    first.id(),
                                    added.id(),
                                    new HoldingUpdate(new BigDecimal("0.000000001"), null)));
            service.delete("owner-a", first.id(), added.id());
            assertTrue(service.holdings("owner-a", first.id()).isEmpty());
            assertEquals(1, service.holdings("owner-a", second.id()).size());
            assertEquals(1, holdings.count());
            tx.rollback();
        }
    }
}
