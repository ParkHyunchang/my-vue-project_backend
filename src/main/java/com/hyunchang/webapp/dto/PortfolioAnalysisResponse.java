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

    private String summary;          // 포트폴리오 전체 한 줄 평가
    private String sentiment;        // "긍정" | "중립" | "부정"
    private List<HoldingAction> holdings;
    private List<Recommendation> recommendations;
    private String disclaimer;

    private Instant retryAt;
    private List<AiProviderChain.ProviderStatus> providersStatus;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HoldingAction {
        private String symbol;
        private String name;
        private String market;
        /** TAKE_PROFIT | HOLD | CUT_LOSS | WATCH */
        private String action;
        /** 현 시점 평가손익률(%) — 백엔드가 같이 채워 프론트에 전달 */
        private Double currentPnlPct;
        private String reason;
        private String newsHint;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Recommendation {
        /** "TOP10" — 시총 Top10 풀에서 선정, "FREE" — LLM 자유 추천 */
        private String source;
        private String symbol;
        private String name;
        private String market;       // "KR" | "US"
        private String reason;
        private String risks;
        private String fitForPortfolio;
    }
}
