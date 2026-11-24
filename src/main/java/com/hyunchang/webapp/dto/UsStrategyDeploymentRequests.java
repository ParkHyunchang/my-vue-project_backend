package com.hyunchang.webapp.dto;

import jakarta.validation.constraints.*;

public final class UsStrategyDeploymentRequests {
    private UsStrategyDeploymentRequests() {}

    public record ImportRequest(
            @NotBlank @Size(max = 1048576) String payloadJson,
            @Pattern(regexp = "[a-f0-9]{64}") @NotNull String signature) {}

    public record ApproveRequest(
            @NotBlank String candidateId,
            @Min(0) long expectedRevision,
            @NotBlank String reportId,
            @NotBlank String version,
            @AssertTrue boolean confirmLive) {}

    public record RevokeRequest(@Min(0) long expectedRevision) {}
}
