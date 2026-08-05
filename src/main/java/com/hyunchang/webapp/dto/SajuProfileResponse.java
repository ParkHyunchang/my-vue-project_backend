package com.hyunchang.webapp.dto;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.hyunchang.webapp.entity.SajuProfile;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 저장된 사주 프로필 조회 응답. Entity를 그대로 내보내면 소유자(User) 연관관계까지 직렬화 대상이 되므로 화면이 쓰는 필드만 노출한다.
 *
 * <p>paljaJson은 이미 JSON 문자열로 저장된 사주팔자(SajuResultDto)라서 {@link JsonRawValue}로 escape 없이 객체 그대로 내보낸다
 * — Entity에서 쓰던 방식과 동일하므로 프론트 파싱 코드는 그대로 둔다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SajuProfileResponse {
    private Long id;
    private String label;
    private LocalDate birthDate; // 음력 입력이어도 항상 환산된 양력 날짜
    private LocalTime birthTime; // timeUnknown=true 면 null
    private boolean timeUnknown;
    private String calendarType; // SOLAR | LUNAR

    // 아래 4개는 calendarType=LUNAR 일 때만 채워진다 (원본 음력 입력값).
    private Integer lunarYear;
    private Integer lunarMonth;
    private Integer lunarDay;
    private boolean leapMonth;

    @JsonRawValue private String paljaJson; // 계산된 사주팔자 JSON

    private String lastReportMarkdown; // 마지막 AI 해석 리포트 (마크다운)
    private LocalDateTime analyzedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static SajuProfileResponse from(SajuProfile profile) {
        return SajuProfileResponse.builder()
                .id(profile.getId())
                .label(profile.getLabel())
                .birthDate(profile.getBirthDate())
                .birthTime(profile.getBirthTime())
                .timeUnknown(profile.isTimeUnknown())
                .calendarType(profile.getCalendarType())
                .lunarYear(profile.getLunarYear())
                .lunarMonth(profile.getLunarMonth())
                .lunarDay(profile.getLunarDay())
                .leapMonth(profile.isLeapMonth())
                .paljaJson(profile.getPaljaJson())
                .lastReportMarkdown(profile.getLastReportMarkdown())
                .analyzedAt(profile.getAnalyzedAt())
                .createdAt(profile.getCreatedAt())
                .updatedAt(profile.getUpdatedAt())
                .build();
    }
}
