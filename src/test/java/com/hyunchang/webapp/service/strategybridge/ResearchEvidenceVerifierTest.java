package com.hyunchang.webapp.service.strategybridge;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class ResearchEvidenceVerifierTest {
    private static final Instant NOW = Instant.parse("2026-11-23T02:00:00Z");
    // Explicit test-only signing material. Never loaded from environment or a live account.
    private static final byte[] KEY =
            "test-only-research-evidence-key-32bytes".getBytes(StandardCharsets.UTF_8);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ResearchEvidenceVerifier verifier = new ResearchEvidenceVerifier(mapper);

    private ObjectNode common(String kind) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schema", ResearchEvidenceVerifier.SCHEMA);
        root.put("kind", kind);
        root.put("engineVersion", ResearchEvidenceVerifier.ENGINE_VERSION);
        root.put("researchCodeHash", ResearchEvidenceVerifier.RESEARCH_CODE_HASH);
        root.put("issuedAt", NOW.minusSeconds(60).toString());
        root.put("expiresAt", NOW.plusSeconds(3600).toString());
        root.put("dataThrough", "2026-11-20");
        root.putObject("risk")
                .put("cashOnly", true)
                .put("allHoldings", true)
                .put("drawdownLimitPct", 10);
        return root;
    }

    private ObjectNode candidate() throws Exception {
        ObjectNode root = common("CANDIDATE");
        root.put("candidateId", "rotation1:recovery_vol");
        root.put("name", "Test candidate");
        root.put("reportId", "a".repeat(64));
        root.put("registeredAt", "2026-06-01T00:00:00Z");
        String canonical =
                "{\"candidate\":\"rotation1\",\"code\":\""
                        + ResearchEvidenceVerifier.RESEARCH_CODE_HASH
                        + "\",\"costBps\":25,\"policy\":\"recovery_vol\"}";
        root.put(
                "version",
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(canonical.getBytes(StandardCharsets.UTF_8))));
        ObjectNode historical =
                root.putObject("historical").put("passed", true).put("auditPassed", true);
        var periods = historical.putArray("periods");
        for (String label : new String[] {"recent", "latest", "continuous"})
            for (int cost : new int[] {10, 25})
                periods.addObject()
                        .put("period", label)
                        .put("costBps", cost)
                        .put("annualizedPct", 10)
                        .put("maxDrawdownPct", 9)
                        .put("hardLatched", false)
                        .put("pendingLiquidation", false);
        root.putObject("forward")
                .put("days", 60)
                .put("fillCount", 20)
                .put("returnPct", 5)
                .put("maxDrawdownPct", 4)
                .put("hardLatched", false);
        root.putObject("baseline")
                .put("returnPct", 3)
                .put("maxDrawdownPct", 3)
                .put("hardLatched", false);
        return root;
    }

    private ObjectNode market() {
        ObjectNode root = common("MARKET");
        ObjectNode signals = root.putObject("signals");
        for (String symbol : ResearchStrategyEngine.SYMBOLS)
            signals.putObject(symbol)
                    .put("eligible", true)
                    .put("momentum", .1)
                    .put("vol20", .2)
                    .put("vol63", .3)
                    .put("riskrank", .4);
        return root;
    }

    private String sign(String raw) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)));
    }

    private void verify(ObjectNode root) throws Exception {
        String raw = mapper.writeValueAsString(root);
        verifier.verify(raw, sign(raw), KEY, root.path("kind").asText(), NOW);
    }

    private void reject(ObjectNode root) {
        assertThrows(IllegalArgumentException.class, () -> verify(root));
    }

    @Test
    void acceptsOnlyProperlySignedFixedVersionEvidence() throws Exception {
        verify(candidate());
        verify(market());
        ObjectNode root = candidate();
        root.put("version", "b".repeat(64));
        reject(root);
        root = candidate();
        root.put("researchCodeHash", "b".repeat(64));
        reject(root);
        root = candidate();
        root.put("engineVersion", "generated-code");
        reject(root);
    }

    @Test
    void rawPayloadTamperingWrongKeyAndWhitespaceChangesFailSignature() throws Exception {
        String raw = mapper.writeValueAsString(candidate());
        String signature = sign(raw);
        assertThrows(
                IllegalArgumentException.class,
                () -> verifier.verify(raw + " ", signature, KEY, "CANDIDATE", NOW));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        verifier.verify(
                                raw.replace("Test candidate", "Changed"),
                                signature,
                                KEY,
                                "CANDIDATE",
                                NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> verifier.verify(raw, signature, new byte[32], "CANDIDATE", NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> verifier.verify(raw, signature, KEY, "MARKET", NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> verifier.verify(raw, signature, KEY, null, NOW));
    }

    @Test
    void duplicateKeysAndTrailingDocumentsAreRejectedEvenWhenSigned() throws Exception {
        String raw = mapper.writeValueAsString(market());
        String duplicate = "{\"kind\":\"MARKET\"," + raw.substring(1);
        assertThrows(
                IllegalArgumentException.class,
                () -> verifier.verify(duplicate, sign(duplicate), KEY, "MARKET", NOW));
        String trailing = raw + " {}";
        assertThrows(
                IllegalArgumentException.class,
                () -> verifier.verify(trailing, sign(trailing), KEY, "MARKET", NOW));
    }

    @Test
    void validityAndCompletedDataWindowsAreStrict() throws Exception {
        for (String through : new String[] {"2026-11-23", "2026-11-24", "2026-11-18"}) {
            ObjectNode root = market();
            root.put("dataThrough", through);
            reject(root);
        }
        ObjectNode root = market();
        root.put("expiresAt", NOW.toString());
        reject(root);
        root = market();
        root.put("expiresAt", NOW.plusSeconds(86400).toString());
        reject(root);
        root = market();
        root.put("issuedAt", NOW.plusSeconds(31).toString());
        reject(root);
    }

    @Test
    void missingPeriodOrFailedSingleCostCannotHideBehindPassedFlag() throws Exception {
        ObjectNode root = candidate();
        ((ObjectNode) root.path("historical").path("periods").get(5)).put("annualizedPct", 9.99);
        reject(root);
        root = candidate();
        ((ObjectNode) root.path("historical").path("periods").get(5)).put("costBps", 10);
        reject(root);
        root = candidate();
        ((ObjectNode) root.path("historical").path("periods").get(0)).put("hardLatched", true);
        reject(root);
        root = candidate();
        ((ObjectNode) root.path("historical").path("periods").get(0)).put("maxDrawdownPct", -1);
        reject(root);
    }

    @Test
    void observationAndRelativeImprovementCannotBeBypassed() throws Exception {
        ObjectNode root = candidate();
        ((ObjectNode) root.path("forward")).put("days", 59);
        reject(root);
        root = candidate();
        ((ObjectNode) root.path("forward")).put("fillCount", 19);
        reject(root);
        root = candidate();
        ((ObjectNode) root.path("forward")).put("returnPct", 4.99);
        reject(root);
        root = candidate();
        ((ObjectNode) root.path("forward")).put("maxDrawdownPct", 4.01);
        reject(root);
        root = candidate();
        ((ObjectNode) root.path("baseline")).put("hardLatched", true);
        reject(root);
    }

    @Test
    void riskAndMarketInputsCannotWidenTheContract() throws Exception {
        ObjectNode root = market();
        ((ObjectNode) root.path("risk")).put("drawdownLimitPct", 10.1);
        reject(root);
        root = market();
        ((ObjectNode) root.path("risk")).put("cashOnly", false);
        reject(root);
        root = market();
        ((ObjectNode) root.path("signals")).remove("IEF");
        reject(root);
        root = market();
        ((ObjectNode) root.path("signals")).putObject("AAPL");
        reject(root);
        root = market();
        ((ObjectNode) root.path("signals").path("SPY")).put("eligible", "true");
        reject(root);
        root = market();
        ((ObjectNode) root.path("signals").path("SPY")).put("vol20", -1);
        reject(root);
        root = market();
        ((ObjectNode) root.path("signals").path("SPY")).put("momentum", Double.NaN);
        reject(root);
    }
}
