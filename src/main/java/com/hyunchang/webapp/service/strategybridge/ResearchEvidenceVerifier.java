package com.hyunchang.webapp.service.strategybridge;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Verifies the signed research contract without reading accounts or accepting executable code. */
public final class ResearchEvidenceVerifier {
    public static final String SCHEMA = "us-research-deployment-v1";
    public static final String ENGINE_VERSION = "team24-daily-v1";
    public static final String RESEARCH_CODE_HASH =
            "607b0cec83aaf45c14a57b607cfb1c1b4a54da21503565c9900433ac755e084b";
    private static final Set<String> KINDS = Set.of("CANDIDATE", "MARKET");
    private static final Set<String> PERIODS = Set.of("recent", "latest", "continuous");
    private static final int MAX_BYTES = 1_048_576;
    private final ObjectMapper mapper;

    public ResearchEvidenceVerifier(ObjectMapper mapper) {
        this.mapper = mapper.copy();
        this.mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public JsonNode verify(
            String payloadJson, String signature, byte[] key, String expectedKind, Instant now) {
        require(payloadJson != null && payloadJson.length() <= MAX_BYTES, "Invalid evidence size");
        byte[] raw = payloadJson.getBytes(StandardCharsets.UTF_8);
        require(raw.length <= MAX_BYTES, "Invalid evidence size");
        require(key != null && key.length >= 32, "Research signing key is not configured");
        require(
                signature != null && signature.matches("[0-9a-fA-F]{64}"),
                "Invalid evidence signature");
        require(
                expectedKind != null && KINDS.contains(expectedKind) && now != null,
                "Invalid evidence kind or clock");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            require(
                    MessageDigest.isEqual(mac.doFinal(raw), HexFormat.of().parseHex(signature)),
                    "Evidence signature mismatch");
            JsonNode root = mapper.readTree(raw);
            require(root != null && root.isObject(), "Evidence must be an object");
            require(SCHEMA.equals(text(root, "schema")), "Unsupported research schema");
            require(expectedKind.equals(text(root, "kind")), "Unexpected research evidence kind");
            require(
                    ENGINE_VERSION.equals(text(root, "engineVersion")),
                    "Unsupported execution engine");
            require(
                    RESEARCH_CODE_HASH.equals(text(root, "researchCodeHash")),
                    "Unreviewed research code");
            Instant issued = Instant.parse(text(root, "issuedAt"));
            Instant expires = Instant.parse(text(root, "expiresAt"));
            require(
                    !issued.isAfter(now.plusSeconds(30))
                            && expires.isAfter(now)
                            && expires.isAfter(issued)
                            && !expires.isAfter(issued.plus(24, ChronoUnit.HOURS)),
                    "Research evidence is expired or has invalid timestamps");
            LocalDate through = LocalDate.parse(text(root, "dataThrough"));
            LocalDate today = now.atOffset(ZoneOffset.UTC).toLocalDate();
            require(
                    through.isBefore(today) && !through.isBefore(today.minusDays(4)),
                    "A recent completed UTC market day is required");
            JsonNode risk = object(root, "risk");
            require(
                    bool(risk, "cashOnly")
                            && bool(risk, "allHoldings")
                            && number(risk, "drawdownLimitPct") == 10,
                    "Cash-only, all-holdings and 10% risk policy must be preserved");
            if (expectedKind.equals("CANDIDATE")) validateCandidate(root, issued);
            else validateMarket(root);
            return root;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException(
                    "Malformed or unverifiable research evidence", error);
        }
    }

    private void validateCandidate(JsonNode root, Instant issued) throws Exception {
        String id = text(root, "candidateId");
        String[] parts = id.split(":", -1);
        require(
                parts.length == 2
                        && ResearchStrategyEngine.CANDIDATES.contains(parts[0])
                        && ResearchStrategyEngine.POLICIES.contains(parts[1]),
                "Unregistered research strategy");
        String name = text(root, "name");
        require(!name.isBlank() && name.length() <= 200, "Invalid candidate name");
        require(text(root, "reportId").matches("[0-9a-f]{64}"), "Invalid research report identity");
        String canonical =
                "{\"candidate\":\""
                        + parts[0]
                        + "\",\"code\":\""
                        + RESEARCH_CODE_HASH
                        + "\",\"costBps\":25,\"policy\":\""
                        + parts[1]
                        + "\"}";
        String version =
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        require(
                version.equals(text(root, "version")),
                "Candidate version does not match reviewed rules");
        Instant registered = Instant.parse(text(root, "registeredAt"));
        require(!registered.isAfter(issued), "Invalid forward observation registration");
        JsonNode historical = object(root, "historical");
        require(
                bool(historical, "passed") && bool(historical, "auditPassed"),
                "Historical evidence did not pass independent review");
        JsonNode periods = historical.get("periods");
        require(
                periods != null && periods.isArray() && periods.size() == 6,
                "All three periods and both cost conditions are required");
        Set<String> seen = new HashSet<>();
        for (JsonNode period : periods) {
            require(period.isObject(), "Invalid period evidence");
            String label = text(period, "period");
            int cost = integer(period, "costBps");
            require(
                    PERIODS.contains(label)
                            && (cost == 10 || cost == 25)
                            && seen.add(label + ":" + cost),
                    "Missing, duplicate or unregistered period/cost evidence");
            require(
                    number(period, "annualizedPct") >= 10
                            && drawdown(period) <= 10
                            && !bool(period, "hardLatched")
                            && !bool(period, "pendingLiquidation"),
                    "Historical return or loss criteria failed");
        }
        JsonNode forward = object(root, "forward");
        JsonNode baseline = object(root, "baseline");
        require(
                integer(forward, "days") >= 60 && integer(forward, "fillCount") >= 20,
                "Forward observation or fill count is insufficient");
        double forwardReturn = number(forward, "returnPct");
        require(
                forwardReturn > 0
                        && forwardReturn >= number(baseline, "returnPct") + 2
                        && drawdown(forward) <= 10
                        && drawdown(forward) <= drawdown(baseline) + 1
                        && !bool(forward, "hardLatched")
                        && !bool(baseline, "hardLatched"),
                "Forward improvement or risk criteria failed");
    }

    private void validateMarket(JsonNode root) {
        JsonNode signals = object(root, "signals");
        Set<String> symbols = new HashSet<>();
        signals.fieldNames().forEachRemaining(symbols::add);
        require(
                symbols.equals(Set.copyOf(ResearchStrategyEngine.SYMBOLS)),
                "Exactly four registered ETF signals are required");
        for (String symbol : ResearchStrategyEngine.SYMBOLS) {
            JsonNode value = object(signals, symbol);
            bool(value, "eligible");
            number(value, "momentum");
            number(value, "riskrank");
            require(
                    number(value, "vol20") >= 0 && number(value, "vol63") >= 0,
                    "Invalid completed-market volatility");
        }
    }

    private static double drawdown(JsonNode value) {
        double n = number(value, "maxDrawdownPct");
        require(n >= 0, "Negative drawdown is not valid evidence");
        return n;
    }

    private static JsonNode object(JsonNode value, String field) {
        JsonNode child = value.get(field);
        require(child != null && child.isObject(), "Missing evidence object: " + field);
        return child;
    }

    private static String text(JsonNode value, String field) {
        JsonNode child = value.get(field);
        require(child != null && child.isTextual(), "Missing evidence text: " + field);
        return child.textValue();
    }

    private static boolean bool(JsonNode value, String field) {
        JsonNode child = value.get(field);
        require(child != null && child.isBoolean(), "Missing evidence boolean: " + field);
        return child.booleanValue();
    }

    private static double number(JsonNode value, String field) {
        JsonNode child = value.get(field);
        require(
                child != null && child.isNumber() && Double.isFinite(child.doubleValue()),
                "Invalid evidence number: " + field);
        return child.doubleValue();
    }

    private static int integer(JsonNode value, String field) {
        JsonNode child = value.get(field);
        require(
                child != null && child.isIntegralNumber() && child.canConvertToInt(),
                "Invalid evidence integer: " + field);
        return child.intValue();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
