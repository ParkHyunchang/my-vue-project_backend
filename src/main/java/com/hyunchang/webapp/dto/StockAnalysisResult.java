package com.hyunchang.webapp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** AI 가 파싱해서 돌려준 분석 본문. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockAnalysisResult {
    private String sentiment;       // "긍정" | "중립" | "부정"
    private String headline;        // 한 줄 핵심 요약
    private List<String> keywords;  // 이슈 키워드
    private List<String> positives; // 호재
    private List<String> risks;     // 리스크
    private String comment;         // 2~3문장 종합
}
