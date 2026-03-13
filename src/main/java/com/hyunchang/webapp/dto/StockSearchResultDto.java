package com.hyunchang.webapp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockSearchResultDto {
    private String symbol;
    private String name;
    private String exchange;
    private String type;     // EQUITY, ETF, INDEX, ...
    private String market;   // KR / US (자동 감지)
}
