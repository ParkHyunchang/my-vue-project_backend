package com.hyunchang.webapp.dto;

import com.hyunchang.webapp.entity.Career;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 공개 홈 화면(/api/public/career)에서 쓰는 경력 응답. 인증 없이 열리는 API라 노출 범위를 어드민 CRUD와 분리한다 —
 * sortOrder/createdAt/updatedAt 같은 관리용 필드는 내보내지 않는다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CareerPublicResponse {
    private Long id;
    private String icon;
    private String company;
    private String period;
    private String badge;
    private String roleDesc;
    private String projects; // JSON 배열 문자열 — 프론트에서 parseJson으로 파싱
    private String tags; // JSON 배열 문자열

    public static CareerPublicResponse from(Career career) {
        return CareerPublicResponse.builder()
                .id(career.getId())
                .icon(career.getIcon())
                .company(career.getCompany())
                .period(career.getPeriod())
                .badge(career.getBadge())
                .roleDesc(career.getRoleDesc())
                .projects(career.getProjects())
                .tags(career.getTags())
                .build();
    }
}
