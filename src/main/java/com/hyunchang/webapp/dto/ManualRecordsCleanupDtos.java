package com.hyunchang.webapp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public final class ManualRecordsCleanupDtos {
    private ManualRecordsCleanupDtos() {}

    public record CleanupRequest(
            @NotBlank @Pattern(regexp = "DELETE_MANUAL_RECORDS") String confirmation) {}

    public record Counts(
            long stock,
            long general,
            long isa,
            long irp,
            long accountHoldings,
            long accounts,
            long total) {
        public static Counts of(
                long stock, long general, long isa, long irp, long accountHoldings, long accounts) {
            return new Counts(
                    stock,
                    general,
                    isa,
                    irp,
                    accountHoldings,
                    accounts,
                    stock + general + isa + irp + accountHoldings + accounts);
        }
    }
}
