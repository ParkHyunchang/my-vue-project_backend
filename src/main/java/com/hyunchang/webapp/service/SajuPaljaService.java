package com.hyunchang.webapp.service;

import com.hyunchang.webapp.dto.SajuBirthInputDto;
import com.hyunchang.webapp.dto.SajuPillarDto;
import com.hyunchang.webapp.dto.SajuResultDto;
import com.hyunchang.webapp.service.saju.SajuCalendarClient;
import com.hyunchang.webapp.util.GanjiTables;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 사주팔자(년주·월주·일주·시주) 계산 서비스.
 *
 * <p>년주·월주·일주는 {@link SajuCalendarClient}(KASI 음양력 정보 API)가 내려준 공식 간지를 그대로 쓰고, 시주만 일간 기준
 * 시두법(오둔시결)으로 로컬 계산한다. 전통 관례에 따라 23:00~23:59 출생(야자시)은 다음날 간지를 사용한다. 음력 생년월일이 입력되면 먼저 양력으로 환산한 뒤 동일한
 * 로직을 그대로 적용한다.
 */
@Service
public class SajuPaljaService {

    // 윤달 등으로 월건이 비어 있을 때 근접 날짜에서 대체값을 찾는 탐색 범위(일). 정확한 절기 경계는 알 수 없어 근사치다.
    private static final int MONTH_GANJI_FALLBACK_RANGE_DAYS = 20;

    private final SajuCalendarClient calendarClient;

    public SajuPaljaService(SajuCalendarClient calendarClient) {
        this.calendarClient = calendarClient;
    }

    public boolean isConfigured() {
        return calendarClient.isConfigured();
    }

    public Optional<SajuResultDto> compute(SajuBirthInputDto input) {
        if (input == null) return Optional.empty();

        LocalDate solarDate;
        SajuCalendarClient.GanjiRaw baseGanji;

        if (input.isLunar()) {
            if (input.lunarYear() == null
                    || input.lunarMonth() == null
                    || input.lunarDay() == null) {
                return Optional.empty();
            }
            SajuCalendarClient.LunarResolution resolved =
                    calendarClient.resolveLunar(
                            input.lunarYear(),
                            input.lunarMonth(),
                            input.lunarDay(),
                            input.leapMonth());
            if (resolved == null) return Optional.empty();
            solarDate = resolved.solarDate();
            baseGanji = resolved.ganji();
        } else {
            if (input.solarDate() == null) return Optional.empty();
            solarDate = input.solarDate();
            baseGanji = null;
        }

        boolean useTime = !input.timeUnknown() && input.birthTime() != null;
        // 야자시(23:00~23:59) 관례: 일주(및 그날의 년주·월주)는 다음날 간지를 사용
        LocalDate effectiveDate =
                (useTime && input.birthTime().getHour() == 23) ? solarDate.plusDays(1) : solarDate;

        SajuCalendarClient.GanjiRaw raw =
                (baseGanji != null && effectiveDate.equals(solarDate))
                        ? baseGanji
                        : calendarClient.fetch(effectiveDate);
        if (raw == null) return Optional.empty();

        SajuPillarDto yearPillar = GanjiTables.parse(raw.yearGanji());
        SajuPillarDto dayPillar = GanjiTables.parse(raw.dayGanji());
        if (yearPillar == null || dayPillar == null) return Optional.empty();

        SajuPillarDto monthPillar = resolveMonthPillar(raw.monthGanji(), effectiveDate);
        if (monthPillar == null) return Optional.empty();

        SajuPillarDto hourPillar = null;
        if (useTime) {
            int branchIdx = GanjiTables.hourToBranchIndex(input.birthTime().getHour());
            hourPillar = GanjiTables.hourPillar(dayPillar.getStemKr(), branchIdx);
        }

        SajuResultDto result =
                SajuResultDto.builder()
                        .solarBirthDate(solarDate)
                        .yearPillar(yearPillar)
                        .monthPillar(monthPillar)
                        .dayPillar(dayPillar)
                        .hourPillar(hourPillar)
                        .fiveElementCounts(
                                countFiveElements(yearPillar, monthPillar, dayPillar, hourPillar))
                        .build();
        return Optional.of(result);
    }

    /**
     * 윤달 기간에는 KASI API가 월건(月建)을 빈 문자열로 돌려준다 — 윤달이 그 해의 어느 절기 구간에 속하는지 이 API로는 알 수 없기 때문이다. 정확한 절기
     * 경계 계산 없이는 해소할 수 없으므로, 가장 가까운 날짜(앞뒤 번갈아 최대 {@value #MONTH_GANJI_FALLBACK_RANGE_DAYS}일)의 월건을
     * 근사값으로 빌려온다. 절기 경계가 윤달 중간에 걸치면 실제와 다를 수 있다.
     */
    private SajuPillarDto resolveMonthPillar(String monthGanji, LocalDate date) {
        if (monthGanji != null) return GanjiTables.parse(monthGanji);
        for (int offset = 1; offset <= MONTH_GANJI_FALLBACK_RANGE_DAYS; offset++) {
            SajuCalendarClient.GanjiRaw forward = calendarClient.fetch(date.plusDays(offset));
            if (forward != null && forward.monthGanji() != null) {
                return GanjiTables.parse(forward.monthGanji());
            }
            SajuCalendarClient.GanjiRaw backward = calendarClient.fetch(date.minusDays(offset));
            if (backward != null && backward.monthGanji() != null) {
                return GanjiTables.parse(backward.monthGanji());
            }
        }
        return null;
    }

    private Map<String, Integer> countFiveElements(SajuPillarDto... pillars) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String el : new String[] {"목", "화", "토", "금", "수"}) {
            counts.put(el, 0);
        }
        for (SajuPillarDto p : pillars) {
            if (p == null) continue;
            counts.merge(p.getStemElement(), 1, Integer::sum);
            counts.merge(p.getBranchElement(), 1, Integer::sum);
        }
        return counts;
    }
}
