package com.hyunchang.webapp.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hyunchang.webapp.common.security.MenuAccessGuard;
import com.hyunchang.webapp.dto.ManualRecordsCleanupDtos.CleanupRequest;
import com.hyunchang.webapp.service.ManualRecordsCleanupService;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class ManualRecordsCleanupAuthorizationTest {
    @Configuration
    @EnableMethodSecurity
    static class Fixture {
        @Bean
        ManualRecordsCleanupService service() {
            return mock(ManualRecordsCleanupService.class);
        }

        @Bean
        MenuAccessGuard access() {
            return mock(MenuAccessGuard.class);
        }

        @Bean
        ManualRecordsCleanupController controller(
                ManualRecordsCleanupService service, MenuAccessGuard access) {
            return new ManualRecordsCleanupController(service, access);
        }
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    void login(String role) {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                "current-admin",
                                "unused",
                                List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @Test
    void onlyAdminWithMenuAccessCanDeleteAndIdentityComesFromAuthentication() {
        try (var context = new AnnotationConfigApplicationContext(Fixture.class)) {
            var controller = context.getBean(ManualRecordsCleanupController.class);
            var service = context.getBean(ManualRecordsCleanupService.class);
            var access = context.getBean(MenuAccessGuard.class);
            var request = new CleanupRequest("DELETE_MANUAL_RECORDS");
            assertThrows(AuthenticationCredentialsNotFoundException.class, controller::summary);
            assertThrows(
                    AuthenticationCredentialsNotFoundException.class,
                    () -> controller.delete(request));
            login("USER");
            assertThrows(AccessDeniedException.class, controller::summary);
            assertThrows(AccessDeniedException.class, () -> controller.delete(request));
            login("ADMIN");
            controller.delete(request);
            controller.summary();
            verifyNoInteractions(service);
            when(access.hasAccess("/portfolio")).thenReturn(true);
            assertEquals("no-store", controller.summary().getHeaders().getCacheControl());
            assertEquals("no-store", controller.delete(request).getHeaders().getCacheControl());
            verify(service).summary("current-admin");
            verify(service).delete("current-admin", "DELETE_MANUAL_RECORDS");
        }
    }
}
