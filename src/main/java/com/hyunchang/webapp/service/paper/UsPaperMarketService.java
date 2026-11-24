package com.hyunchang.webapp.service.paper;

import com.hyunchang.webapp.util.KiwoomUsMarketHours;
import java.time.*;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "us-paper.enabled", havingValue = "true")
public class UsPaperMarketService {
    public static final ZoneId ET = ZoneId.of("America/New_York");
    public static final Duration MAX_QUOTE_AGE = Duration.ofMinutes(5);
    // 2025 warmup: NYSE 2025 calendar plus the January 9 national mourning closure.
    private static final Set<LocalDate> CLOSED_2025 =
            Set.of(
                    LocalDate.of(2025, 1, 1),
                    LocalDate.of(2025, 1, 9),
                    LocalDate.of(2025, 1, 20),
                    LocalDate.of(2025, 2, 17),
                    LocalDate.of(2025, 4, 18),
                    LocalDate.of(2025, 5, 26),
                    LocalDate.of(2025, 6, 19),
                    LocalDate.of(2025, 7, 4),
                    LocalDate.of(2025, 9, 1),
                    LocalDate.of(2025, 11, 27),
                    LocalDate.of(2025, 12, 25));
    private static final Map<LocalDate, LocalTime> EARLY =
            Map.of(
                    LocalDate.of(2026, 11, 27),
                    LocalTime.of(13, 0),
                    LocalDate.of(2026, 12, 24),
                    LocalTime.of(13, 0),
                    LocalDate.of(2027, 11, 26),
                    LocalTime.of(13, 0),
                    LocalDate.of(2028, 7, 3),
                    LocalTime.of(13, 0),
                    LocalDate.of(2028, 11, 24),
                    LocalTime.of(13, 0));

    public boolean isOpen(Instant at) {
        var local = at.atZone(ET);
        return KiwoomUsMarketHours.isTradingDay(local.toLocalDate())
                && !local.toLocalTime().isBefore(LocalTime.of(9, 30))
                && local.toLocalTime()
                        .isBefore(EARLY.getOrDefault(local.toLocalDate(), LocalTime.of(16, 0)));
    }

    public boolean isFresh(Instant quotedAt, Instant now) {
        return quotedAt != null
                && !quotedAt.isAfter(now.plusSeconds(30))
                && !quotedAt.isBefore(now.minus(MAX_QUOTE_AGE));
    }

    public LocalDate previousSession(Instant now) {
        return previousSession(now.atZone(ET).toLocalDate());
    }

    public boolean isTradingDay(LocalDate day) {
        if (day.getYear() == 2025)
            return day.getDayOfWeek() != DayOfWeek.SATURDAY
                    && day.getDayOfWeek() != DayOfWeek.SUNDAY
                    && !CLOSED_2025.contains(day);
        return KiwoomUsMarketHours.isTradingDay(day);
    }

    public LocalDate previousSession(LocalDate day) {
        LocalDate previous = day.minusDays(1);
        for (int i = 0; i < 14; i++, previous = previous.minusDays(1))
            if (isTradingDay(previous)) return previous;
        throw new IllegalStateException("등록된 미국 거래일 달력이 없습니다.");
    }
}
