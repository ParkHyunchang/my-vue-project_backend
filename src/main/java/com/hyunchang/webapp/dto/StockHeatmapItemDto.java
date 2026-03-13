package com.hyunchang.webapp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockHeatmapItemDto {
    private String symbol;
    private String name;
    private double price;
    private double changePercent;
    private long   marketCap;
}
