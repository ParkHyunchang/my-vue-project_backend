package com.hyunchang.webapp.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hyunchang.webapp.entity.*;
import com.hyunchang.webapp.repository.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class ManualRecordsCleanupServiceTest {
    SessionFactory factory;
    TransactionTemplate tx;
    JpaRepositoryFactory repos;
    ManualRecordsCleanupService service;
    IrpHoldingRepository irp;

    @BeforeEach
    void setup() {
        var config = new Configuration();
        for (var type :
                new Class<?>[] {
                    User.class,
                    StockHolding.class,
                    GeneralHolding.class,
                    IsaHolding.class,
                    IrpHolding.class,
                    PortfolioAccount.class,
                    AccountHolding.class,
                    KiwoomAccountHolding.class
                }) config.addAnnotatedClass(type);
        factory =
                config.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
                        .setProperty(
                                "hibernate.connection.url",
                                "jdbc:h2:mem:cleanup_" + UUID.randomUUID())
                        .setProperty("hibernate.connection.username", "sa")
                        .setProperty("hibernate.connection.password", "")
                        .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                        .buildSessionFactory();
        var manager = new JpaTransactionManager(factory);
        tx = new TransactionTemplate(manager);
        var em = SharedEntityManagerCreator.createSharedEntityManager(factory);
        repos = new JpaRepositoryFactory(em);
        irp =
                mock(
                        IrpHoldingRepository.class,
                        org.mockito.AdditionalAnswers.delegatesTo(
                                repos.getRepository(IrpHoldingRepository.class)));
        var target =
                new ManualRecordsCleanupService(
                        repos.getRepository(StockHoldingRepository.class),
                        repos.getRepository(GeneralHoldingRepository.class),
                        repos.getRepository(IsaHoldingRepository.class),
                        irp,
                        repos.getRepository(AccountHoldingRepository.class),
                        repos.getRepository(PortfolioAccountRepository.class));
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(
                new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        service = (ManualRecordsCleanupService) proxy.getProxy();
        tx.executeWithoutResult(
                status -> {
                    for (String owner : new String[] {"owner-a", "owner-b"}) {
                        User user =
                                User.builder()
                                        .userId(owner)
                                        .name(owner)
                                        .email(owner + "@example.invalid")
                                        .password("test-only")
                                        .role("ADMIN")
                                        .build();
                        em.persist(user);
                        StockHolding stock = new StockHolding();
                        stock.setUser(user);
                        stock.setMarket("KR");
                        stock.setName("Manual");
                        stock.setSymbol("005930");
                        stock.setQuantity(1L);
                        em.persist(stock);
                        GeneralHolding general = new GeneralHolding();
                        general.setUser(user);
                        general.setMarket("KR");
                        general.setName("Cash");
                        general.setSymbol("CASH");
                        general.setQuantity(100L);
                        general.setAssetType("CASH");
                        em.persist(general);
                        IsaHolding isa = new IsaHolding();
                        isa.setUser(user);
                        isa.setMarket("KR");
                        isa.setName("Manual");
                        isa.setSymbol("005930");
                        isa.setQuantity(1L);
                        em.persist(isa);
                        IrpHolding pension = new IrpHolding();
                        pension.setUser(user);
                        pension.setMarket("KR");
                        pension.setName("Manual");
                        pension.setSymbol("005930");
                        pension.setQuantity(1L);
                        em.persist(pension);
                        PortfolioAccount account = new PortfolioAccount();
                        account.setUser(user);
                        account.setAccountKey("custom");
                        account.setBroker("KIWOOM");
                        account.setName("Still manual");
                        account.setPurpose("OTHER");
                        em.persist(account);
                        AccountHolding holding = new AccountHolding();
                        holding.setAccount(account);
                        holding.setAssetType("STOCK");
                        holding.setMarket("US");
                        holding.setName("Manual");
                        holding.setSymbol("AAPL");
                        holding.setQuantity(new BigDecimal("0.25"));
                        em.persist(holding);
                    }
                    KiwoomAccountHolding broker = new KiwoomAccountHolding();
                    broker.updateFrom(
                            "005930", "Broker snapshot", 7, 7, 100, 110, 10, LocalDateTime.now());
                    em.persist(broker);
                });
    }

    @AfterEach
    void close() {
        if (factory != null) factory.close();
    }

    @Test
    void deletesOnlyCurrentUsersManualRecordsIncludingCashAndManualKiwoomLabel() {
        assertEquals(6, service.summary("owner-a").total());
        var deleted = service.delete("owner-a", "DELETE_MANUAL_RECORDS");
        assertEquals(6, deleted.total());
        assertEquals(1, deleted.accountHoldings());
        assertEquals(1, deleted.general());
        assertEquals(0, service.summary("owner-a").total());
        assertEquals(6, service.summary("owner-b").total());
        assertEquals(0, service.delete("owner-a", "DELETE_MANUAL_RECORDS").total());
        tx.executeWithoutResult(
                status -> {
                    var em = SharedEntityManagerCreator.createSharedEntityManager(factory);
                    var broker =
                            em.createQuery("from KiwoomAccountHolding", KiwoomAccountHolding.class)
                                    .getSingleResult();
                    assertEquals(7, broker.getQuantity());
                    assertEquals("Broker snapshot", broker.getStockName());
                });
    }

    @Test
    void failureAtLastDeleteRollsBackAllEarlierDeletes() {
        doThrow(new IllegalStateException("simulated failure"))
                .when(irp)
                .deleteManualByUserId("owner-a");
        assertThrows(
                IllegalStateException.class,
                () -> service.delete("owner-a", "DELETE_MANUAL_RECORDS"));
        assertEquals(6, service.summary("owner-a").total());
        assertEquals(6, service.summary("owner-b").total());
    }

    @Test
    void requiresExactConfirmationAndAuthenticatedIdentity() {
        for (String confirmation :
                new String[] {null, "", "delete_manual_records", "DELETE_MANUAL_RECORDS "})
            assertThrows(
                    ResponseStatusException.class, () -> service.delete("owner-a", confirmation));
        assertThrows(
                ResponseStatusException.class,
                () -> service.delete("unknown", "DELETE_MANUAL_RECORDS"));
        assertEquals(6, service.summary("owner-a").total());
    }
}
