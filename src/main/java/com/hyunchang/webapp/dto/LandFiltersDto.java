package com.hyunchang.webapp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 토지 검색 결과에 등장하는 지목·용도지역 목록 (보유 등록/검색 필터 자동완성용).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LandFiltersDto {
    private List<String> jimoks;    // 지목 목록 (가나다순, 중복 제거)
    private List<String> useZones;  // 용도지역 목록 (가나다순, 중복 제거)
}
