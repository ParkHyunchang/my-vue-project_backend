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

    private String summary;          // 포트폴리오 전체 평가 (투자위원회 보고 톤, 2~3문장)
    private String sentiment;        // "긍정" | "중립" | "부정"
    private String macroFit;         // 현재 거시 국면에서의 유불리 한두 문장
    private Grades grades;           // 분산 / 리스크 / 성장성 등급
    private List<HoldingAction> holdings;
    private KeyHolding coreHolding;  // 가장 강한 핵심 종목
    private KeyHolding weakestLink;  // 가장 취약한 고리
    private List<Recommendation> recommendations;
    private List<String> priorityActions; // 우선순위 액션 3가지
    private Scenario bullScenario;   // 강세 시나리오
    private Scenario bearScenario;   // 약세 시나리오
    private String selfRebuttal;     // 자기반박 — "내가 틀린다면"
    private String disclaimer;

    private Instant retryAt;
    private List<AiProviderChain.ProviderStatus> providersStatus;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Grades {
        private GradeItem diversification; // 분산
        private GradeItem risk;            // 리스크
        private GradeItem growth;          // 성장성
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GradeItem {
        private String grade;   // "A" ~ "F"
        private String comment; // 한 문장 근거
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KeyHolding {
        private String symbol;
        private String name;
        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Scenario {
        private String trigger; // 촉발 요인
        private String outlook; // 전개 전망
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HoldingAction {
        private String symbol;
        private String name;
        private String market;
        /** ADD | TAKE_PROFIT | HOLD | CUT_LOSS | WATCH */
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
