package com.hyunchang.webapp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 토지 실거래 1건 (국토교통부 토지 매매 실거래가 공개 API 기반).
 * 토지는 단지명이 없고 지번(필지)·지목·용도지역으로 식별한다.
 * 금액 단위는 만원(국토부 API 원본 단위 유지).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LandDealDto {
    private String sigungu;        // 시군구명 (검색한 지역)
    private String dong;           // 법정동 (예: 역삼동)
    private String jimok;          // 지목 (전/답/대/임야 등)
    private String useZone;        // 용도지역 (계획관리/주거지역/농림 등)
    private double areaM2;         // 거래면적(㎡)
    private long dealAmount;       // 거래금액(만원)
    private String dealDate;       // 거래일 YYYY-MM-DD
    private String sharePartition; // 지분구분 (예: 일반/지분)

    /** 거래 단가 (원/㎡). 면적이 0이면 0. */
    public long getPricePerM2() {
        if (areaM2 <= 0) return 0;
        return Math.round(dealAmount * 10000.0 / areaM2);
    }
}
