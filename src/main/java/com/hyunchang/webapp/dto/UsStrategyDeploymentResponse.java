package com.hyunchang.webapp.dto;

import java.util.List;

public record UsStrategyDeploymentResponse(
        boolean bridgeEnabled,
        long revision,
        String accountScope,
        boolean managed,
        Active active,
        Candidate candidate,
        List<String> blockers,
        String lastExecution) {
    public record Active(String candidateId, String version, String reportId, String approvedAt) {}

    public record Candidate(
            String id,
            String candidateId,
            String name,
            String version,
            String reportId,
            double historicalAnnualizedPct,
            double forwardReturnPct,
            int forwardDays,
            int forwardFillCount,
            boolean eligible,
            List<String> reasons) {}
}
