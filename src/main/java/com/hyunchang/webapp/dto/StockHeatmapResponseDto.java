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
public class StockHeatmapResponseDto {
    private String updatedAt; // "2026-03-13 14:30" 형식
    private List<StockHeatmapSectorDto> sectors;
}
