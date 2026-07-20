package com.hyunchang.webapp.util;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

/** 한국 주식 정규장 시간 판정. 공휴일(휴장일)은 걸러내지 못한다 — 주말·시간대만 확인한다. */
public final class KiwoomMarketHours {
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private KiwoomMarketHours() {}

    public static boolean isOpen() {
        LocalDateTime now = LocalDateTime.now(KST);
        LocalTime t = now.toLocalTime();
        return now.getDayOfWeek() != DayOfWeek.SATURDAY
                && now.getDayOfWeek() != DayOfWeek.SUNDAY
                && !t.isBefore(LocalTime.of(9, 0))
                && !t.isAfter(LocalTime.of(15, 30));
    }
}
