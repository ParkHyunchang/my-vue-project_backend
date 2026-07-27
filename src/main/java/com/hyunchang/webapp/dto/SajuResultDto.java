package com.hyunchang.webapp.dto;

import java.time.LocalDate;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 사주팔자 계산 결과 — 년주/월주/일주/시주 + 오행 분포. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SajuResultDto {
    private LocalDate solarBirthDate; // 계산에 사용된 양력 기준일 (음력 입력이면 환산된 양력 날짜)
    private SajuPillarDto yearPillar;
    private SajuPillarDto monthPillar;
    private SajuPillarDto dayPillar;
    private SajuPillarDto hourPillar; // 태어난 시간을 모르면 null
    private Map<String, Integer> fiveElementCounts; // 목/화/토/금/수 → 개수(0~8)
}
