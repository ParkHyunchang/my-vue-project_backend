package com.hyunchang.webapp.service.strategybridge;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/** Durable, authenticated approval boundary. Implementations must never reset account risk. */
public interface UsResearchDeploymentGateway {
    boolean enabled();

    /** True after initial approval, including revoked/paused state; no legacy fallback. */
    boolean managed();

    /** Guard all account/risk/holdings access, even when a deployment is disabled or revoked. */
    void requireAccountScope();

    /** Throws unless active approval and signed, complete, fresh market evidence are available. */
    ExecutionContext executionContext();

    /**
     * Atomically claims the active deployment/day and saves the next strategy state before orders.
     */
    boolean claim(
            String deploymentId,
            LocalDate signalDate,
            String expectedMarketHash,
            String nextStateJson);

    /** Durable execution outcome. No automatic retry of a claimed day, including failures. */
    void finish(String deploymentId, LocalDate signalDate, String outcome, String detail);

    record ExecutionContext(
            String deploymentId,
            String candidateId,
            String version,
            LocalDate signalDate,
            Instant marketGeneratedAt,
            Map<String, ResearchStrategyEngine.Signal> signals,
            String stateJson,
            String marketHash) {}
}
