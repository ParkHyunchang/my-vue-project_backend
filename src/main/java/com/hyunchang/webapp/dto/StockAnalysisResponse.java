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
public class StockAnalysisResponse {
    private boolean blocked;                  // true 면 모든 provider 차단
    private String providerName;              // 성공한 provider 이름
    private String model;                     // 사용한 모델
    private Instant analyzedAt;               // 분석 완료 시각
    private String report;                    // AI 마크다운 분석 리포트 (성공 시)
    private StockAnalysisResult result;       // (구버전) 파싱된 본문 — 자유리포트 전환 후 미사용
    private List<StockNewsDto> sources;       // 참고한 뉴스 (성공 시)
    private Instant retryAt;                  // 다음 가능 시각 (blocked=true 일 때)
    private List<AiProviderChain.ProviderStatus> providersStatus;
}
