package com.hyunchang.webapp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 읍면동(법정동) — 시군구 하위의 법정동 단위. code 는 10자리 법정동코드.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UmdDto {
    private String name;  // 읍면동(+리) 표시명 (예: 청운동, 신동면 정안리)
    private String code;  // 법정동코드 10자리
}
