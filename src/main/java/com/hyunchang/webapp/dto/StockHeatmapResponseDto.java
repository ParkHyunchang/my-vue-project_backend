package com.hyunchang.webapp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockHeatmapResponseDto {
    private String updatedAt;          // "2026-03-13 14:30" 형식
    private List<StockHeatmapSectorDto> sectors;
}
