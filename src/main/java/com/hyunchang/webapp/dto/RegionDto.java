package com.hyunchang.webapp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 법정동 시군구 정보 (국토부 실거래가 API의 LAWD_CD 5자리).
 * resources/realestate/kr-lawd-codes.json 기반.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegionDto {
    private String code;     // 법정동 시군구 코드 5자리 (LAWD_CD)
    private String sido;     // 시/도 (예: 서울특별시)
    private String sigungu;  // 시군구 (예: 강남구, 수원시 영통구)
    private String name;     // 표시명 "시도 시군구" (예: 서울특별시 강남구)
}
