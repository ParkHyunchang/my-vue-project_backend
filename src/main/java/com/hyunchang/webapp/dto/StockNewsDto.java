package com.hyunchang.webapp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockNewsDto {
    private String title;
    private String originalTitle;       // 번역 전 원문 영어 제목 (US 뉴스 필터링용)
    private String link;
    private String description;
    private String originalDescription; // 번역 전 원문 영어 설명 (US 뉴스 필터링용)
    private String pubDate;
    private String source;
    private String market; // "KR" or "US"
    private String imageUrl;
}
