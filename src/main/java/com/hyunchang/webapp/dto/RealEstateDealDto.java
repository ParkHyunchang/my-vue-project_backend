package com.hyunchang.webapp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 아파트 실거래 1건 (국토교통부 실거래가 공개 API 기반). 거래유형(dealType)에 따라 금액 필드 사용이 달라진다: SALE : dealAmount(거래금액) 사용
 * JEONSE : deposit(보증금) 사용, monthlyRent = 0 MONTHLY : deposit(보증금) + monthlyRent(월세) 사용 모든 금액 단위는
 * 만원(국토부 API 원본 단위 유지).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RealEstateDealDto {
    private String dealType; // SALE / JEONSE / MONTHLY
    private String aptName; // 아파트 단지명
    private String sigungu; // 시군구명 (검색한 지역)
    private String dong; // 법정동 (예: 대치동)
    private String jibun; // 지번
    private double areaM2; // 전용면적(㎡)
    private int floor; // 층
    private int buildYear; // 건축년도
    private String dealDate; // 거래일 YYYY-MM-DD
    private long dealAmount; // 거래금액(만원) — 매매
    private long deposit; // 보증금(만원) — 전세/월세
    private long monthlyRent; // 월세(만원) — 월세
}
