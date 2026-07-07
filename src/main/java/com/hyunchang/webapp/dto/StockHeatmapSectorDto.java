package com.hyunchang.webapp.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockHeatmapSectorDto {
    private String sector;
    private List<StockHeatmapItemDto> stocks;
}
