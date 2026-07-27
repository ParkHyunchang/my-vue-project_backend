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
public class SajuAnalysisResponse {
    private Long profileId; // 저장된 프로필 ID (즉석 계산이면 null)
    private boolean found; // 사주팔자 계산 성공 여부 (false 면 KASI 미설정/실패)
    private String message; // 실패 사유 (found=false 일 때)
    private SajuResultDto palja; // 계산된 사주팔자 (found=true)
    private boolean blocked; // true 면 모든 AI provider 차단
    private String providerName;
    private String model;
    private Instant analyzedAt;
    private String report; // AI 마크다운 해석 리포트 (성공 시)
    private Instant retryAt; // 다음 가능 시각 (blocked=true 일 때)
    private List<AiProviderChain.ProviderStatus> providersStatus;
}
