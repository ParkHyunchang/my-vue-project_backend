package com.hyunchang.webapp.dto;

import com.hyunchang.webapp.entity.GeneralHolding;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeneralHoldingResponse {
    private Long id;
    private String assetType;
    private String market;
    private String name;
    private String symbol;
    private Long quantity;
    private Double avgPrice;
    private boolean core;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static GeneralHoldingResponse from(GeneralHolding holding) {
        return GeneralHoldingResponse.builder()
                .id(holding.getId())
                .assetType(holding.getAssetType())
                .market(holding.getMarket())
                .name(holding.getName())
                .symbol(holding.getSymbol())
                .quantity(holding.getQuantity())
                .avgPrice(holding.getAvgPrice())
                .core(holding.isCore())
                .createdAt(holding.getCreatedAt())
                .updatedAt(holding.getUpdatedAt())
                .build();
    }
}
