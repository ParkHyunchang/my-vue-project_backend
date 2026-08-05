package com.hyunchang.webapp.dto;

import com.hyunchang.webapp.entity.PortfolioSkill;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 공개 홈 화면(/api/public/portfolio-skills)에서 쓰는 스킬 카드 응답. 관리용 필드는 제외한다. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortfolioSkillPublicResponse {
    private Long id;
    private String cssClass; // 카드 색상 클래스 "p1", "p2", ...
    private String title;
    private String descriptions; // JSON 배열 문자열 — 프론트에서 parseJson으로 파싱

    public static PortfolioSkillPublicResponse from(PortfolioSkill skill) {
        return PortfolioSkillPublicResponse.builder()
                .id(skill.getId())
                .cssClass(skill.getCssClass())
                .title(skill.getTitle())
                .descriptions(skill.getDescriptions())
                .build();
    }
}
