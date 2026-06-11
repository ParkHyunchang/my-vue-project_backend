package com.hyunchang.webapp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** AI 가 파싱해서 돌려준 지역 시황 분석 본문. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RealEstateAnalysisResult {
    private String trend;            // "상승" | "보합" | "하락"
    private String headline;         // 한 줄 핵심 요약
    private String priceLevel;       // 현재 시세대 요약 (예: "전용 84㎡ 기준 24~26억대")
    private List<String> keywords;   // 이슈 키워드
    private List<String> watchPoints;// 매수 검토 시 주의점
    private String comment;          // 2~3문장 종합 코멘트
}
