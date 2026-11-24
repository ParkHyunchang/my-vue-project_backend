package com.hyunchang.webapp.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hyunchang.webapp.service.KiwoomAccountOverviewService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class KiwoomAccountOverviewAuthorizationTest {
    @Configuration
    @EnableMethodSecurity
    static class Fixture {
        @Bean
        KiwoomAccountOverviewService service() {
            return mock(KiwoomAccountOverviewService.class);
        }

        @Bean
        KiwoomAccountOverviewController controller(KiwoomAccountOverviewService service) {
            return new KiwoomAccountOverviewController(service);
        }
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anonymousAndUserCannotReadServerAccount() {
        try (var context = new AnnotationConfigApplicationContext(Fixture.class)) {
            var controller = context.getBean(KiwoomAccountOverviewController.class);
            assertThrows(AuthenticationCredentialsNotFoundException.class, controller::overview);
            SecurityContextHolder.getContext()
                    .setAuthentication(
                            new UsernamePasswordAuthenticationToken(
                                    "test-user",
                                    "unused",
                                    List.of(new SimpleGrantedAuthority("ROLE_USER"))));
            assertThrows(AccessDeniedException.class, controller::overview);
            verifyNoInteractions(context.getBean(KiwoomAccountOverviewService.class));
        }
    }

    @Test
    void adminCanReadAndResponseCannotBeCachedByBrowser() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                "test-admin",
                                "unused",
                                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        try (var context = new AnnotationConfigApplicationContext(Fixture.class)) {
            assertEquals(
                    "no-store",
                    context.getBean(KiwoomAccountOverviewController.class)
                            .overview()
                            .getHeaders()
                            .getCacheControl());
            verify(context.getBean(KiwoomAccountOverviewService.class)).overview();
        }
    }
}
