package com.hyunchang.webapp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 개별공시지가 조회 결과 (NSDI 국가공간정보 개별공시지가 API 기반).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OfficialPriceDto {
    private boolean found;       // 공시지가를 찾았는지
    private String pnu;          // 조회에 사용한 PNU(19자리)
    private long pricePerM2;     // 개별공시지가(원/㎡)
    private int year;            // 기준연도
    private String message;      // 실패 시 안내 (선택)
}
