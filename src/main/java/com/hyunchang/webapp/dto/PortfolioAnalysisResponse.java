package com.hyunchang.webapp.dto;

import com.hyunchang.webapp.service.ai.AiProviderChain;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortfolioAnalysisResponse {
    private boolean blocked;
    private String providerName;
    private String model;
    private Instant analyzedAt;

    private String report;      // AI 마크다운 진단 리포트 (메인)

    private String summary;     // 보유 자산 없음 안내 메시지 (빈 포트폴리오 케이스)
    private String sentiment;   // 빈 포트폴리오 케이스 보조 필드
    private String disclaimer;

    private Instant retryAt;
    private List<AiProviderChain.ProviderStatus> providersStatus;
}
