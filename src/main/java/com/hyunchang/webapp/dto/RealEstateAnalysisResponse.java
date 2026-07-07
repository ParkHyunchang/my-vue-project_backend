package com.hyunchang.webapp.dto;

import com.hyunchang.webapp.service.ai.AiProviderChain;
import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RealEstateAnalysisResponse {
    private boolean blocked; // true 면 모든 provider 차단
    private boolean noData; // 집계할 실거래가 없음
    private String providerName;
    private String model;
    private Instant analyzedAt;
    private RealEstateStatsDto stats; // 결정적 집계 (AI 실패해도 항상 제공)
    private RealEstateAnalysisResult result; // AI 분석 본문 (성공 시)
    private List<RealEstateNewsDto> sources; // 참고 뉴스 (성공 시)
    private Instant retryAt;
    private List<AiProviderChain.ProviderStatus> providersStatus;
}
