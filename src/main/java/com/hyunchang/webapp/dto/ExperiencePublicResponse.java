package com.hyunchang.webapp.dto;

import com.hyunchang.webapp.entity.Experience;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 공개 홈 화면(/api/public/experience)에서 쓰는 경험 응답. 관리용 필드(sortOrder, createdAt, updatedAt)는 제외한다. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExperiencePublicResponse {
    private Long id;
    private String title;
    private String subtitle;
    private String description;
    private String period;

    public static ExperiencePublicResponse from(Experience experience) {
        return ExperiencePublicResponse.builder()
                .id(experience.getId())
                .title(experience.getTitle())
                .subtitle(experience.getSubtitle())
                .description(experience.getDescription())
                .period(experience.getPeriod())
                .build();
    }
}
