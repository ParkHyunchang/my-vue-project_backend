package com.hyunchang.webapp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 보유 토지 추정 시세 — 같은 시군구·지목·용도지역의 최근 실거래 단가(원/㎡) 기반. 토지는 필지마다 고유 상품이라 정확한 개별 시세가 아닌 "주변 단가 범위 + 추정가"
 * 를 제공한다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LandQuoteDto {
    private boolean found; // 매칭되는 실거래가 있었는지
    private int matchCount; // 매칭 건수
    private long pricePerM2Min; // 최저 단가(원/㎡)
    private long pricePerM2Avg; // 평균 단가(원/㎡)
    private long pricePerM2Max; // 최고 단가(원/㎡)
    private String recentDate; // 최근 거래일 YYYY-MM-DD
    private long estimate; // 추정 시세(만원) = 평균단가 × 보유면적
}
