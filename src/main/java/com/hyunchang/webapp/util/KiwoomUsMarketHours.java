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

/** 키움이 지원하는 미국 주간·프리·정규·애프터 세션 판정. 미등록 연도는 안전하게 주문을 닫는다. */
public final class KiwoomUsMarketHours {
    public static final ZoneId ET = ZoneId.of("America/New_York");
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalTime DAY_START = LocalTime.of(20, 0);
    private static final LocalTime DAY_END = LocalTime.of(3, 45);
    private static final LocalTime PRE_MARKET_START = LocalTime.of(4, 0);
    private static final LocalTime REGULAR_START = LocalTime.of(9, 30);
    private static final LocalTime AFTER_HOURS_END = LocalTime.of(18, 0);
    private static final LocalTime EARLY_CLOSE_AFTER_HOURS_END = LocalTime.of(17, 0);
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

    public enum Session {
        CLOSED("장 운영시간 아님"),
        DAY("주간거래"),
        PRE_MARKET("프리마켓"),
        REGULAR("정규장"),
        AFTER_HOURS("애프터마켓");

        private final String label;

        Session(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public static boolean isTradingDay(LocalDate date) {
        return date.getYear() >= 2026
                && date.getYear() <= 2028
                && date.getDayOfWeek() != DayOfWeek.SATURDAY
                && date.getDayOfWeek() != DayOfWeek.SUNDAY
                && !CLOSED.contains(date);
    }

    public static boolean isOpen() {
        return currentSession() != Session.CLOSED;
    }

    public static boolean isEntryWindow() {
        return isOpen();
    }

    public static boolean isRegularSession() {
        return currentSession() == Session.REGULAR;
    }

    public static Session currentSession() {
        return sessionAt(LocalDateTime.now(ET));
    }

    static Session sessionAt(LocalDateTime easternNow) {
        LocalDate tradingDate = tradingDate(easternNow);
        if (!isTradingDay(tradingDate)) return Session.CLOSED;
        LocalTime time = easternNow.toLocalTime();
        if (!time.isBefore(DAY_START) || time.isBefore(DAY_END)) return Session.DAY;
        if (time.isBefore(PRE_MARKET_START)) return Session.CLOSED;
        if (time.isBefore(REGULAR_START)) return Session.PRE_MARKET;
        LocalTime regularClose =
                EARLY_CLOSE.getOrDefault(tradingDate, LocalTime.of(16, 0));
        if (time.isBefore(regularClose)) return Session.REGULAR;
        LocalTime afterHoursClose =
                EARLY_CLOSE.containsKey(tradingDate)
                        ? EARLY_CLOSE_AFTER_HOURS_END
                        : AFTER_HOURS_END;
        return time.isBefore(afterHoursClose) ? Session.AFTER_HOURS : Session.CLOSED;
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
        return tradingDate(LocalDateTime.now(ET));
    }

    private static LocalDate tradingDate(LocalDateTime easternNow) {
        return easternNow.toLocalTime().isBefore(DAY_START)
                ? easternNow.toLocalDate()
                : easternNow.toLocalDate().plusDays(1);
    }

    public static LocalDate previousTradingDay(LocalDate date) {
        LocalDate previous = date.minusDays(1);
        for (int i = 0; i < 14; i++, previous = previous.minusDays(1)) {
            if (isTradingDay(previous)) return previous;
        }
        throw new IllegalStateException("이전 미국 거래일을 확인할 수 없습니다.");
    }

    /** Entry date excluded; ET holidays/weekends do not consume a holding session. */
    public static int elapsedTradingDays(LocalDate entry, LocalDate today) {
        if (entry == null || today == null || today.isBefore(entry))
            throw new IllegalArgumentException("보유 거래일 계산에 유효한 날짜가 필요합니다.");
        if (entry.getYear() < 2026 || today.getYear() > 2028)
            throw new IllegalStateException("등록되지 않은 거래일 달력입니다.");
        int days = 0;
        for (LocalDate date = entry.plusDays(1); !date.isAfter(today); date = date.plusDays(1))
            if (isTradingDay(date)) days++;
        return days;
    }

    public static LocalDateTime now() {
        return LocalDateTime.now(ET);
    }

    /** DB LocalDateTime과 같은 시스템 시간대로 환산한 현재 미국 거래일의 시작 시각. */
    public static LocalDateTime currentTradingDateStartInSystemZone() {
        return today().atStartOfDay(KST)
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
        return isDaylightSavingTime()
                ? "주간 09:00~16:45 · 프리 17:00~22:30 · 정규 22:30~익일 05:00 · 애프터 익일 05:00~07:00"
                : "주간 10:00~17:45 · 프리 18:00~23:30 · 정규 23:30~익일 06:00 · 애프터 익일 06:00~08:00";
    }

    public static String currentSessionLabel() {
        return currentSession().label();
    }
}
