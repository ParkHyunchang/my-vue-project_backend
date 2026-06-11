package com.hyunchang.webapp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 지역+거래유형 실거래 집계 통계 (AI 호출과 무관하게 항상 계산해 반환).
 * 금액 단위: 만원. 매매=거래금액 / 전세·월세=보증금 기준.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RealEstateStatsDto {
    private String sigungu;
    private String dealType;
    private int totalCount;       // 집계 거래 건수
    private long avgPrice;        // 평균가
    private long minPrice;        // 최저가
    private long maxPrice;        // 최고가
    private long avgMonthlyRent;  // 평균 월세 (월세 거래만)
    private double trendPct;      // 최근 절반 vs 이전 절반 평균가 변동률(%)
    private String trendLabel;    // 상승 / 보합 / 하락
    private List<AreaBucket> areaBuckets; // 평형(전용면적 10㎡ 구간)별 집계

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AreaBucket {
        private String label;   // 예: "80~90㎡"
        private int count;
        private long avgPrice;
    }
}
