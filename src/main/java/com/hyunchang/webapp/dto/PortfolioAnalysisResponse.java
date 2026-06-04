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
        /** "NEWS" — 최근 시장 뉴스 헤드라인에 근거해 선정 */
        private String source;
        private String symbol;
        private String name;
        private String market;       // "KR" | "US"
        /** 이미 보유 중인 종목인지 (추가 매수 관점 추천) — 서버가 실제 보유 종목과 대조해 채움 */
        private boolean held;
        /** 추천 근거가 된 뉴스 헤드라인 원문 */
        private String newsBasis;
        private String reason;
        private String risks;
        private String fitForPortfolio;
    }
}
