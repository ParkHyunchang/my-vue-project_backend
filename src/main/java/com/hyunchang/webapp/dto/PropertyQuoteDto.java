package com.hyunchang.webapp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 보유 부동산 추정 시세 — 국토부 최근 실거래가 기반. 같은 시군구·단지명·전용면적(±오차) 의 최근 거래로 추정한다. 단위: 만원. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PropertyQuoteDto {
    private boolean found; // 매칭되는 실거래가 있었는지
    private int matchCount; // 매칭 건수
    private String recentDate; // 최근 거래일 YYYY-MM-DD
    private long recentPrice; // 최근 거래가(매매=거래금액 / 전세·월세=보증금)
    private long recentMonthlyRent; // 최근 월세 (월세 거래만)
    private long avgPrice; // 매칭 거래 평균가
}
