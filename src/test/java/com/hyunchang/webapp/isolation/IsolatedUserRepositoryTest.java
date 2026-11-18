package com.hyunchang.webapp.isolation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hyunchang.webapp.entity.User;
import com.hyunchang.webapp.repository.UserRepository;
import java.util.UUID;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

/** Real entity/repository CRUD, without Boot, component scanning, runners, or schedulers. */
class IsolatedUserRepositoryTest {
    @Test
    void userCrudUsesOnlyDisposableInMemoryDatabase() {
        String databaseUrl = "jdbc:h2:mem:team01_" + UUID.randomUUID();
        Configuration configuration =
                new Configuration()
                        .addAnnotatedClass(User.class)
                        .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
                        .setProperty("hibernate.connection.url", databaseUrl)
                        .setProperty("hibernate.connection.username", "sa")
                        .setProperty("hibernate.connection.password", "")
                        .setProperty("hibernate.hbm2ddl.auto", "create-drop");

        try (var factory = configuration.buildSessionFactory();
                var session = factory.openSession()) {
            session.doWork(
                    connection ->
                            assertTrue(connection.getMetaData().getURL().startsWith(databaseUrl)));
            UserRepository repository =
                    new JpaRepositoryFactory(session).getRepository(UserRepository.class);
            var transaction = session.beginTransaction();
            User user =
                    User.builder()
                            .userId("isolated-user")
                            .name("Test User")
                            .email("isolated@example.invalid")
                            .password("not-a-real-password")
                            .role("USER")
                            .build();
            repository.saveAndFlush(user);
            session.clear();
            User saved = repository.findByUserId("isolated-user").orElseThrow();
            assertEquals("Test User", saved.getName());
            saved.setName("Updated Test User");
            repository.saveAndFlush(saved);
            session.clear();
            assertEquals(
                    "Updated Test User",
                    repository.findByEmail("isolated@example.invalid").orElseThrow().getName());
            repository.deleteById(saved.getId());
            repository.flush();
            assertFalse(repository.existsByUserId("isolated-user"));
            transaction.commit();
        }
    }
}
