package com.hyunchang.webapp.service.strategybridge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.dto.KiwoomUsRiskResponse;
import com.hyunchang.webapp.dto.UsStrategyDeploymentRequests.RevokeRequest;
import com.hyunchang.webapp.entity.UsStrategyDeployment;
import com.hyunchang.webapp.repository.KiwoomUsTradeProposalRepository;
import com.hyunchang.webapp.repository.UsStrategyDeploymentAuditRepository;
import com.hyunchang.webapp.repository.UsStrategyDeploymentRepository;
import com.hyunchang.webapp.service.KiwoomUsAccountRiskService;
import com.hyunchang.webapp.service.kiwoom.KiwoomUsAutoTradeState;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Independent deployment-state boundaries. All repositories and account controls are mocks. */
class UsStrategyDeploymentBoundaryTest {
    private static final Instant NOW = Instant.parse("2026-11-23T02:00:00Z");
    private static final String TEST_KEY = "test-only-deployment-boundary-key-32bytes";
    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir Path temp;

    private record Fixture(
            UsStrategyDeploymentService service,
            UsStrategyDeployment row,
            KiwoomProperties properties,
            KiwoomUsAutoTradeState control,
            KiwoomUsAccountRiskService risk,
            Path market) {}

    private Fixture fixture(boolean enabled) throws Exception {
        var repository = mock(UsStrategyDeploymentRepository.class);
        var audit = mock(UsStrategyDeploymentAuditRepository.class);
        var proposals = mock(KiwoomUsTradeProposalRepository.class);
        var risk = mock(KiwoomUsAccountRiskService.class);
        var control = mock(KiwoomUsAutoTradeState.class);
        var properties = new KiwoomProperties();
        properties.setAccountNo("TEST-ACCOUNT-A");
        var row = new UsStrategyDeployment();
        row.setRevision(7L);
        row.setManaged(true);
        row.setActive(true);
        row.setDeploymentId("approved-test-deployment");
        row.setAccountHash(UsStrategyDeploymentService.sha("US:TEST-ACCOUNT-A"));
        row.setLastExecution("APPROVED: test fixture only");
        row.setActivePayload(
                mapper.createObjectNode()
                        .put("candidateId", "rotation1:recovery_vol")
                        .put("version", "fixture-version")
                        .put("reportId", "a".repeat(64))
                        .put("researchCodeHash", ResearchEvidenceVerifier.RESEARCH_CODE_HASH)
                        .put("engineVersion", ResearchEvidenceVerifier.ENGINE_VERSION)
                        .toString());
        when(repository.findById(1L)).thenReturn(Optional.of(row));
        when(repository.findForUpdate()).thenReturn(Optional.of(row));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(proposals.findByStatusIn(any())).thenReturn(List.of());
        when(risk.status())
                .thenReturn(
                        new KiwoomUsRiskResponse(
                                true,
                                new BigDecimal("10000"),
                                new BigDecimal("10000"),
                                BigDecimal.ZERO,
                                10,
                                false,
                                false,
                                true,
                                NOW.atOffset(ZoneOffset.UTC),
                                "test"));
        Path key = temp.resolve("test-signing-key");
        Path market = temp.resolve("test-market.json");
        Files.writeString(key, TEST_KEY);
        writeMarket(market, ResearchEvidenceVerifier.RESEARCH_CODE_HASH);
        var service =
                new UsStrategyDeploymentService(
                        repository,
                        audit,
                        proposals,
                        risk,
                        control,
                        properties,
                        mapper,
                        enabled,
                        key.toString(),
                        market.toString());
        service.useClock(Clock.fixed(NOW, ZoneOffset.UTC));
        return new Fixture(service, row, properties, control, risk, market);
    }

    private void writeMarket(Path destination, String sourceHash) throws Exception {
        var root =
                mapper.createObjectNode()
                        .put("schema", ResearchEvidenceVerifier.SCHEMA)
                        .put("kind", "MARKET")
                        .put("engineVersion", ResearchEvidenceVerifier.ENGINE_VERSION)
                        .put("researchCodeHash", sourceHash)
                        .put("issuedAt", NOW.minusSeconds(60).toString())
                        .put("expiresAt", NOW.plusSeconds(3600).toString())
                        .put("dataThrough", "2026-11-20");
        root.putObject("risk")
                .put("cashOnly", true)
                .put("allHoldings", true)
                .put("drawdownLimitPct", 10);
        var signals = root.putObject("signals");
        for (String symbol : ResearchStrategyEngine.SYMBOLS)
            signals.putObject(symbol)
                    .put("eligible", true)
                    .put("momentum", .1)
                    .put("vol20", .1)
                    .put("vol63", .2)
                    .put("riskrank", .5);
        String payload = root.toString();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(TEST_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature =
                HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        Files.writeString(
                destination,
                mapper.createObjectNode()
                        .put("payloadJson", payload)
                        .put("signature", signature)
                        .toString());
    }

    @Test
    void disablingConnectionCannotReactivateLegacyTradingOrUseApprovedContext() throws Exception {
        Fixture f = fixture(false);
        assertTrue(f.service.managed());
        assertFalse(f.service.enabled());
        assertThrows(IllegalArgumentException.class, f.service::executionContext);
        verifyNoInteractions(f.control, f.risk);
    }

    @Test
    void changingConfiguredAccountBlocksPreviouslyApprovedContext() throws Exception {
        Fixture f = fixture(true);
        assertEquals("rotation1:recovery_vol", f.service.executionContext().candidateId());
        f.properties.setAccountNo("TEST-ACCOUNT-B");
        assertTrue(f.service.managed());
        assertThrows(IllegalArgumentException.class, f.service::executionContext);
    }

    @Test
    void changedResearchCodeIsRejectedEvenWithValidSignature() throws Exception {
        Fixture f = fixture(true);
        writeMarket(f.market, "b".repeat(64));
        assertThrows(IllegalArgumentException.class, f.service::executionContext);
    }

    @Test
    void claimedOrFailedExecutionCannotBeReplayedAfterReconstruction() throws Exception {
        Fixture f = fixture(true);
        f.row.setLastExecution("CLAIMED: 2026-11-20");
        assertThrows(IllegalArgumentException.class, f.service::executionContext);
        f.row.setLastExecution("FAILED: uncertain broker outcome");
        assertThrows(IllegalArgumentException.class, f.service::executionContext);
    }

    @Test
    void revokeDoesNotClearManagedModeExecutionDayOrRiskAndDoesNotStartTrading() throws Exception {
        Fixture f = fixture(true);
        f.row.setExecutionState("{\"preserve\":true}");
        f.row.setClaimedSignalDate("2026-11-20");
        f.service.revoke(new RevokeRequest(7));
        assertTrue(f.row.isManaged());
        assertFalse(f.row.isActive());
        assertEquals("{\"preserve\":true}", f.row.getExecutionState());
        assertEquals("2026-11-20", f.row.getClaimedSignalDate());
        verify(f.control, never()).setAutoTrading(anyBoolean());
        verify(f.risk, never()).activate(any(), anyBoolean(), anyBoolean());
        verify(f.risk, never()).observe(any());
    }

    @Test
    void staleDeploymentAndAlreadyClaimedDateCannotClaimOrders() throws Exception {
        Fixture f = fixture(true);
        when(f.control.isAutoTrading()).thenReturn(true);
        String marketHash = f.service.executionContext().marketHash();
        assertFalse(
                f.service.claim(
                        "superseded-deployment", LocalDate.parse("2026-11-20"), marketHash, "{}"));
        f.row.setClaimedSignalDate("2026-11-20");
        assertFalse(
                f.service.claim(
                        f.row.getDeploymentId(), LocalDate.parse("2026-11-20"), marketHash, "{}"));
        assertNull(f.row.getExecutionState());
    }

    @Test
    void sameDayMarketReplacementInvalidatesPreviouslyCalculatedPlan() throws Exception {
        Fixture f = fixture(true);
        when(f.control.isAutoTrading()).thenReturn(true);
        assertFalse(
                f.service.claim(
                        f.row.getDeploymentId(),
                        LocalDate.parse("2026-11-20"),
                        "old-market-hash",
                        "{}"));
        assertNull(f.row.getClaimedSignalDate());
        assertNull(f.row.getExecutionState());
    }
}
