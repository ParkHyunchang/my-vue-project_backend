package com.hyunchang.webapp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockQuotaDto {
    private int dailyLimit;       // 하루 총 한도 (25)
    private int callsUsed;        // 오늘 사용한 호출 수
    private int callsRemaining;   // 남은 호출 수
    private String nextKrRefresh; // 다음 KR 자동 갱신 시각
    private String nextUsRefresh; // 다음 US 자동 갱신 시각
    private String resetInfo;     // 자정 초기화까지 남은 시간
}
