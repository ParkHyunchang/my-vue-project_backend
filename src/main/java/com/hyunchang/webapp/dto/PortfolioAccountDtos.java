package com.hyunchang.webapp.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;

public final class PortfolioAccountDtos {
    private PortfolioAccountDtos() {}

    public record AccountRequest(
            @NotBlank @Size(max = 20) String broker,
            @NotBlank @Size(max = 80) String name,
            @NotBlank @Size(max = 20) String purpose) {}

    public record AccountResponse(
            String id,
            String broker,
            String name,
            String purpose,
            String legacyType,
            String source,
            String holdingsPath) {}

    public record HoldingRequest(
            @NotBlank String assetType,
            @NotBlank String market,
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 20) String symbol,
            @NotNull @DecimalMin("0.00000001") @Digits(integer = 16, fraction = 8)
                    BigDecimal quantity,
            @DecimalMin("0") @Digits(integer = 16, fraction = 8) BigDecimal avgPrice) {}

    public record HoldingUpdate(
            @NotNull @DecimalMin("0.00000001") @Digits(integer = 16, fraction = 8)
                    BigDecimal quantity,
            @DecimalMin("0") @Digits(integer = 16, fraction = 8) BigDecimal avgPrice) {}

    public record CoreUpdate(@NotNull Boolean core) {}

    public record HoldingResponse(
            Long id,
            String accountType,
            String assetType,
            String market,
            String currency,
            String name,
            String symbol,
            BigDecimal quantity,
            BigDecimal avgPrice,
            boolean core,
            String source,
            Instant updatedAt) {}
}
