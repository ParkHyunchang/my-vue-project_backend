package com.hyunchang.webapp.service.strategybridge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.dto.KiwoomUsRiskResponse;
import com.hyunchang.webapp.dto.UsStrategyDeploymentRequests.*;
import com.hyunchang.webapp.entity.UsStrategyDeployment;
import com.hyunchang.webapp.repository.*;
import com.hyunchang.webapp.service.KiwoomUsAccountRiskService;
import com.hyunchang.webapp.service.kiwoom.KiwoomUsAutoTradeState;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class UsStrategyDeploymentServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-23T02:00:00Z");
    @TempDir Path folder;
    private final ObjectMapper json = new ObjectMapper();
    private final UsStrategyDeploymentRepository repo = mock(UsStrategyDeploymentRepository.class);
    private final UsStrategyDeploymentAuditRepository audit =
            mock(UsStrategyDeploymentAuditRepository.class);
    private final KiwoomUsTradeProposalRepository proposals =
            mock(KiwoomUsTradeProposalRepository.class);
    private final KiwoomUsAccountRiskService risk = mock(KiwoomUsAccountRiskService.class);
    private final KiwoomUsAutoTradeState control = mock(KiwoomUsAutoTradeState.class);
    private final KiwoomProperties properties = new KiwoomProperties();
    private UsStrategyDeployment row;
    private UsStrategyDeploymentService service;
    private Path market;

    @BeforeEach
    void setup() throws Exception {
        properties.setAccountNo("TEST-ACCOUNT-ONLY");
        Path key = folder.resolve("key");
        Files.writeString(key, "test-only-research-evidence-key-32bytes");
        market = folder.resolve("market.json");
        Files.writeString(market, resource("market"));
        when(repo.findById(1L)).thenAnswer(i -> Optional.ofNullable(row));
        when(repo.findForUpdate()).thenAnswer(i -> Optional.ofNullable(row));
        when(repo.saveAndFlush(any()))
                .thenAnswer(
                        i -> {
                            UsStrategyDeployment saved = i.getArgument(0);
                            saved.setRevision(
                                    saved.getRevision() == null ? 0 : saved.getRevision() + 1);
                            row = saved;
                            return saved;
                        });
        when(proposals.findByStatusIn(any())).thenReturn(List.of());
        when(risk.status())
                .thenReturn(
                        new KiwoomUsRiskResponse(
                                true,
                                new BigDecimal("9000"),
                                new BigDecimal("9500"),
                                new BigDecimal("5.26"),
                                10,
                                false,
                                false,
                                true,
                                NOW.atOffset(ZoneOffset.UTC),
                                "TEST"));
        service =
                new UsStrategyDeploymentService(
                        repo,
                        audit,
                        proposals,
                        risk,
                        control,
                        properties,
                        json,
                        true,
                        key.toString(),
                        market.toString());
        service.useClock(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private String resource(String type) throws Exception {
        try (var in =
                getClass().getResourceAsStream("/strategybridge/python-signed-" + type + ".json")) {
            assertNotNull(in);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private ImportRequest evidence() throws Exception {
        JsonNode data = json.readTree(resource("candidate"));
        return new ImportRequest(
                data.path("payloadJson").asText(), data.path("signature").asText());
    }

    private ApproveRequest request() {
        var s = service.status();
        return new ApproveRequest(
                s.candidate().id(),
                s.revision(),
                s.candidate().reportId(),
                s.candidate().version(),
                true);
    }

    private void approve() throws Exception {
        service.importCandidate(evidence());
        service.approve(request());
    }

    @Test
    void pythonSignedUnicodeReportImportsAndApprovalNeverStartsOrResetsAccount() throws Exception {
        var imported = service.importCandidate(evidence());
        assertNull(imported.active());
        assertFalse(imported.managed());
        assertTrue(imported.candidate().eligible());
        assertTrue(imported.candidate().name().contains("합성"));
        ApproveRequest request = request();
        var approved = service.approve(request);
        assertTrue(approved.managed());
        assertEquals("rotation1:recovery_vol", approved.active().candidateId());
        assertNull(approved.candidate());
        assertThrows(IllegalArgumentException.class, () -> service.approve(request));
        verify(control, never()).setAutoTrading(anyBoolean());
        verify(risk, never()).activate(any(), anyBoolean(), anyBoolean());
        verify(risk, never()).observe(any());
        assertEquals(2, mockingDetails(audit).getInvocations().size());
    }

    @Test
    void staleRevisionIdentityAndMissingConsentCannotApprove() throws Exception {
        service.importCandidate(evidence());
        var request = request();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.approve(
                                new ApproveRequest(
                                        request.candidateId(),
                                        request.expectedRevision(),
                                        request.reportId(),
                                        request.version(),
                                        false)));
        service.importCandidate(evidence());
        assertThrows(IllegalArgumentException.class, () -> service.approve(request));
        var newer = request();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.approve(
                                new ApproveRequest(
                                        newer.candidateId(),
                                        newer.expectedRevision(),
                                        newer.reportId(),
                                        "wrong-version",
                                        true)));
        assertFalse(service.managed());
    }

    @Test
    void changedAccountAndExpiredEvidenceFailClosed() throws Exception {
        service.importCandidate(evidence());
        var request = request();
        properties.setAccountNo("DIFFERENT-TEST-ACCOUNT");
        assertThrows(IllegalArgumentException.class, () -> service.approve(request));
        properties.setAccountNo("TEST-ACCOUNT-ONLY");
        service.useClock(Clock.fixed(NOW.plusSeconds(7200), ZoneOffset.UTC));
        assertFalse(service.status().candidate().eligible());
        assertThrows(IllegalArgumentException.class, () -> service.approve(request));
    }

    @Test
    void signedMarketFlowsIntoExecutionAndClaimsAreBoundToEvidenceAndApproval() throws Exception {
        approve();
        var ctx = service.executionContext();
        assertEquals(4, ctx.signals().size());
        assertEquals(.1, ctx.signals().get("SPY").momentum());
        when(control.isAutoTrading()).thenReturn(true);
        assertFalse(service.claim("other-deployment", ctx.signalDate(), ctx.marketHash(), "{}"));
        assertFalse(service.claim(ctx.deploymentId(), ctx.signalDate(), "changed-market", "{}"));
        assertTrue(
                service.claim(
                        ctx.deploymentId(), ctx.signalDate(), ctx.marketHash(), "{\"kept\":true}"));
        assertThrows(IllegalArgumentException.class, () -> service.executionContext());
        service.finish(ctx.deploymentId(), ctx.signalDate(), "COMPLETED", "test-only no orders");
        assertFalse(service.claim(ctx.deploymentId(), ctx.signalDate(), ctx.marketHash(), "{}"));
        assertEquals("{\"kept\":true}", service.executionContext().stateJson());
    }

    @Test
    void failedOrInterruptedExecutionCannotBeClearedByImportOrAutomaticRetry() throws Exception {
        approve();
        var ctx = service.executionContext();
        when(control.isAutoTrading()).thenReturn(true);
        service.claim(ctx.deploymentId(), ctx.signalDate(), ctx.marketHash(), "{}");
        when(control.isAutoTrading()).thenReturn(false);
        service.importCandidate(evidence());
        assertThrows(IllegalArgumentException.class, () -> service.approve(request()));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.revoke(new RevokeRequest(service.status().revision())));
        service.finish(ctx.deploymentId(), ctx.signalDate(), "FAILED", "test failure");
        assertThrows(IllegalArgumentException.class, () -> service.executionContext());
        service.approve(request()); // Explicit review permitted after a definite failed outcome.
        assertEquals(ctx.signalDate().toString(), row.getClaimedSignalDate());
        assertEquals("{}", row.getExecutionState());
    }

    @Test
    void revocationKeepsManagedBoundaryAndExecutionHistory() throws Exception {
        approve();
        row.setExecutionState("{\"history\":true}");
        row.setClaimedSignalDate("2026-11-19");
        service.revoke(new RevokeRequest(service.status().revision()));
        assertTrue(service.managed());
        assertFalse(row.isActive());
        assertEquals("{\"history\":true}", row.getExecutionState());
        assertEquals("2026-11-19", row.getClaimedSignalDate());
        assertThrows(IllegalArgumentException.class, () -> service.executionContext());
    }

    @Test
    void missingOrBlockedMarketCannotUsePreviouslyApprovedStrategy() throws Exception {
        approve();
        Files.writeString(market, "{\"blocked\":true}");
        assertThrows(IllegalArgumentException.class, () -> service.executionContext());
        Files.delete(market);
        assertThrows(IllegalArgumentException.class, () -> service.executionContext());
    }
}
