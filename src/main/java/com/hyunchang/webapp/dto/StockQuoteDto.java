package com.hyunchang.webapp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockQuoteDto {
    private int rank;
    private int rankChange;   // 양수 = 순위 상승, 음수 = 하락, 0 = 변동 없음
    private String symbol;
    private String name;
    private double price;
    private double change;
    private double changePercent;
    private long marketCap;   // 실시간 계산값 (가격 × 발행주식수)
    private String currency;
    private long volume;
}
