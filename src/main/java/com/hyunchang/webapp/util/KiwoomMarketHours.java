package com.hyunchang.webapp.util;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** 한국 주식 정규장 시간 판정. 공휴일(휴장일)은 걸러내지 못한다 — 주말·시간대만 확인한다. */
public final class KiwoomMarketHours {
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private KiwoomMarketHours() {}

    public static boolean isOpen() {
        LocalDateTime now = LocalDateTime.now(KST);
        LocalTime t = now.toLocalTime();
        return isTradingDay(now.toLocalDate())
                && !t.isBefore(LocalTime.of(9, 0))
                && !t.isAfter(LocalTime.of(15, 30));
    }

    public static boolean isTradingDay(LocalDate date) {
        if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY)
            return false;
        return !closedDates().contains(date);
    }

    /**
     * KRX has additional annual closure days (lunar holidays, elections, and the last session).
     * KIWOOM_CLOSED_DATES accepts comma-separated ISO dates and is merged with this baseline.
     */
    private static Set<LocalDate> closedDates() {
        Set<LocalDate> dates = new HashSet<>();
        String configured =
                "2026-01-01,2026-02-16,2026-02-17,2026-02-18,2026-03-02,"
                        + "2026-05-01,2026-05-05,2026-05-25,2026-08-17,"
                        + "2026-09-24,2026-09-25,2026-09-28,2026-10-05,"
                        + "2026-10-09,2026-12-25,2026-12-31";
        String overrides = System.getenv("KIWOOM_CLOSED_DATES");
        if (overrides != null && !overrides.isBlank()) configured += "," + overrides;
        for (String value : Arrays.stream(configured.split(",")).toList()) {
            try {
                dates.add(LocalDate.parse(value.trim()));
            } catch (RuntimeException ignored) {
                // An invalid override must not make the market look open.
            }
        }
        return dates;
    }
}
