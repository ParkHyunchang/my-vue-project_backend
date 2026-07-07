package com.hyunchang.webapp.dto;

import com.hyunchang.webapp.service.ai.AiProviderChain;
import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 토지 AI 지역 시황 분석 응답. 결정적 통계(stats)는 AI 실패해도 항상 제공. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LandAnalysisResponse {
    private boolean blocked; // true 면 모든 provider 차단
    private boolean noData; // 집계할 실거래가 없음
    private String providerName;
    private String model;
    private Instant analyzedAt;
    private LandStatsDto stats; // 결정적 단가 집계
    private RealEstateAnalysisResult result; // AI 분석 본문 (성공 시) — 아파트와 동일 스키마 재사용
    private List<RealEstateNewsDto> sources; // 참고 뉴스 (성공 시)
    private Instant retryAt;
    private List<AiProviderChain.ProviderStatus> providersStatus;
}
