package com.hyunchang.webapp.util;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;

/** 미국 정규장 판정. DST는 America/New_York가 처리하며 미등록 연도는 안전하게 주문을 닫는다. */
public final class KiwoomUsMarketHours {
    public static final ZoneId ET = ZoneId.of("America/New_York");
    private static final Set<LocalDate> CLOSED =
            Set.of(
                    LocalDate.parse("2026-01-01"),
                    LocalDate.parse("2026-01-19"),
                    LocalDate.parse("2026-02-16"),
                    LocalDate.parse("2026-04-03"),
                    LocalDate.parse("2026-05-25"),
                    LocalDate.parse("2026-06-19"),
                    LocalDate.parse("2026-07-03"),
                    LocalDate.parse("2026-09-07"),
                    LocalDate.parse("2026-11-26"),
                    LocalDate.parse("2026-12-25"),
                    LocalDate.parse("2027-01-01"),
                    LocalDate.parse("2027-01-18"),
                    LocalDate.parse("2027-02-15"),
                    LocalDate.parse("2027-03-26"),
                    LocalDate.parse("2027-05-31"),
                    LocalDate.parse("2027-06-18"),
                    LocalDate.parse("2027-07-05"),
                    LocalDate.parse("2027-09-06"),
                    LocalDate.parse("2027-11-25"),
                    LocalDate.parse("2027-12-24"),
                    LocalDate.parse("2028-01-17"),
                    LocalDate.parse("2028-02-21"),
                    LocalDate.parse("2028-04-14"),
                    LocalDate.parse("2028-05-29"),
                    LocalDate.parse("2028-06-19"),
                    LocalDate.parse("2028-07-04"),
                    LocalDate.parse("2028-09-04"),
                    LocalDate.parse("2028-11-23"),
                    LocalDate.parse("2028-12-25"));
    private static final Map<LocalDate, LocalTime> EARLY_CLOSE =
            Map.of(
                    LocalDate.parse("2026-11-27"),
                    LocalTime.of(13, 0),
                    LocalDate.parse("2026-12-24"),
                    LocalTime.of(13, 0),
                    LocalDate.parse("2027-11-26"),
                    LocalTime.of(13, 0),
                    LocalDate.parse("2028-07-03"),
                    LocalTime.of(13, 0),
                    LocalDate.parse("2028-11-24"),
                    LocalTime.of(13, 0));

    private KiwoomUsMarketHours() {}

    public static boolean isTradingDay(LocalDate date) {
        return date.getYear() >= 2026
                && date.getYear() <= 2028
                && date.getDayOfWeek() != DayOfWeek.SATURDAY
                && date.getDayOfWeek() != DayOfWeek.SUNDAY
                && !CLOSED.contains(date);
    }

    public static boolean isOpen() {
        LocalDateTime now = LocalDateTime.now(ET);
        if (!isTradingDay(now.toLocalDate())) return false;
        LocalTime close = EARLY_CLOSE.getOrDefault(now.toLocalDate(), LocalTime.of(16, 0));
        return !now.toLocalTime().isBefore(LocalTime.of(9, 30))
                && now.toLocalTime().isBefore(close);
    }

    public static boolean isEntryWindow() {
        LocalDateTime now = LocalDateTime.now(ET);
        if (!isTradingDay(now.toLocalDate())) return false;
        LocalTime close = EARLY_CLOSE.getOrDefault(now.toLocalDate(), LocalTime.of(16, 0));
        LocalTime entryClose = close.minusHours(1);
        return !now.toLocalTime().isBefore(LocalTime.of(10, 0))
                && now.toLocalTime().isBefore(entryClose);
    }

    /** 정규장 전체 시간 대비 현재까지 경과한 비율. 시간대별 상대 거래량 계산에 사용한다. */
    public static double regularSessionProgress() {
        LocalDateTime now = LocalDateTime.now(ET);
        LocalTime open = LocalTime.of(9, 30);
        LocalTime close = EARLY_CLOSE.getOrDefault(now.toLocalDate(), LocalTime.of(16, 0));
        if (!now.toLocalTime().isAfter(open)) return 0;
        if (!now.toLocalTime().isBefore(close)) return 1;
        long totalSeconds = Duration.between(open, close).toSeconds();
        long elapsedSeconds = Duration.between(open, now.toLocalTime()).toSeconds();
        return Math.max(0, Math.min(1, elapsedSeconds / (double) totalSeconds));
    }

    public static LocalDate today() {
        return LocalDate.now(ET);
    }

    public static LocalDateTime now() {
        return LocalDateTime.now(ET);
    }

    /** DB LocalDateTime과 같은 시스템 시간대로 환산한 현재 미국 거래일의 시작 시각. */
    public static LocalDateTime currentTradingDateStartInSystemZone() {
        return today().atStartOfDay(ET)
                .withZoneSameInstant(ZoneId.systemDefault())
                .toLocalDateTime();
    }

    public static boolean isDaylightSavingTime() {
        return ET.getRules().isDaylightSavings(Instant.now());
    }

    public static String seasonLabel() {
        return isDaylightSavingTime() ? "하절기(DST)" : "동절기(표준시)";
    }

    public static String regularSessionKst() {
        return isDaylightSavingTime() ? "22:30~익일 05:00" : "23:30~익일 06:00";
    }

    public static String entrySessionKst() {
        return isDaylightSavingTime() ? "23:00~익일 04:00" : "00:00~05:00";
    }
}
