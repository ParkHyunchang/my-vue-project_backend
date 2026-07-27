package com.hyunchang.webapp.dto;

import java.time.LocalDate;
import java.time.LocalTime;

/** 사주 계산 입력 — 양력/음력 생년월일 + 태어난 시각. */
public record SajuBirthInputDto(
        String calendarType, // "SOLAR" | "LUNAR"
        LocalDate solarDate, // calendarType=SOLAR 일 때 사용
        Integer lunarYear, // calendarType=LUNAR 일 때 사용
        Integer lunarMonth,
        Integer lunarDay,
        boolean leapMonth, // 음력 윤달 여부 (calendarType=LUNAR 일 때만 의미 있음)
        LocalTime birthTime,
        boolean timeUnknown) {

    public boolean isLunar() {
        return "LUNAR".equalsIgnoreCase(calendarType);
    }
}
