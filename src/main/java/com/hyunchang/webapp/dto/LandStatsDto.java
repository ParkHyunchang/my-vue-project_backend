package com.hyunchang.webapp.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 토지 실거래 집계 통계 — 단가(원/㎡) 중심 (AI 호출과 무관하게 항상 계산). 토지는 필지마다 달라 평균가가 아닌 단가 분포·지목/용도지역별 단가가 의미 있다. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LandStatsDto {
    private String sigungu;
    private int totalCount; // 집계 거래 건수
    private long avgPricePerM2; // 평균 단가(원/㎡)
    private long minPricePerM2; // 최저 단가(원/㎡)
    private long maxPricePerM2; // 최고 단가(원/㎡)
    private double trendPct; // 최근 절반 vs 이전 절반 단가 변동률(%)
    private String trendLabel; // 상승 / 보합 / 하락
    private List<Bucket> jimokBuckets; // 지목별 평균 단가
    private List<Bucket> zoneBuckets; // 용도지역별 평균 단가

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Bucket {
        private String label; // 지목명 또는 용도지역명
        private int count;
        private long avgPricePerM2; // 평균 단가(원/㎡)
    }
}
