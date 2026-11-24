package com.hyunchang.webapp.service.strategybridge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hyunchang.webapp.controller.UsStrategyDeploymentController;
import com.hyunchang.webapp.dto.UsStrategyDeploymentRequests.ApproveRequest;
import com.hyunchang.webapp.dto.UsStrategyDeploymentRequests.ImportRequest;
import com.hyunchang.webapp.dto.UsStrategyDeploymentRequests.RevokeRequest;
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

/** Actual method-security proxy only; no HTTP server, scheduler, application scan or database. */
class UsStrategyDeploymentAuthorizationTest {
    @Configuration
    @EnableMethodSecurity
    static class SecurityFixture {
        @Bean
        UsStrategyDeploymentService service() {
            return mock(UsStrategyDeploymentService.class);
        }

        @Bean
        UsStrategyDeploymentController controller(UsStrategyDeploymentService service) {
            return new UsStrategyDeploymentController(service);
        }
    }

    @AfterEach
    void clearIdentity() {
        SecurityContextHolder.clearContext();
    }

    private List<Runnable> operations(UsStrategyDeploymentController controller) {
        return List.of(
                controller::status,
                () -> controller.importCandidate(new ImportRequest("{}", "a".repeat(64))),
                () ->
                        controller.approve(
                                new ApproveRequest("candidate", 1, "report", "version", true)),
                () -> controller.revoke(new RevokeRequest(1)));
    }

    @Test
    void unauthenticatedCallsCannotReachAnyDeploymentOperation() {
        SecurityContextHolder.clearContext();
        try (var context = new AnnotationConfigApplicationContext(SecurityFixture.class)) {
            for (Runnable call : operations(context.getBean(UsStrategyDeploymentController.class)))
                assertThrows(AuthenticationCredentialsNotFoundException.class, call::run);
            verifyNoInteractions(context.getBean(UsStrategyDeploymentService.class));
        }
    }

    @Test
    void ordinaryUserCannotImportApproveRevokeOrInspectDeployment() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                "test-user",
                                "unused",
                                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        try (var context = new AnnotationConfigApplicationContext(SecurityFixture.class)) {
            for (Runnable call : operations(context.getBean(UsStrategyDeploymentController.class)))
                assertThrows(AccessDeniedException.class, call::run);
            verifyNoInteractions(context.getBean(UsStrategyDeploymentService.class));
        }
    }

    @Test
    void adminReachesServiceButNoBrokerOrApplicationIsStarted() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                "test-admin",
                                "unused",
                                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        try (var context = new AnnotationConfigApplicationContext(SecurityFixture.class)) {
            for (Runnable call : operations(context.getBean(UsStrategyDeploymentController.class)))
                assertDoesNotThrow(call::run);
            assertEquals(
                    4,
                    mockingDetails(context.getBean(UsStrategyDeploymentService.class))
                            .getInvocations()
                            .size());
        }
    }
}
