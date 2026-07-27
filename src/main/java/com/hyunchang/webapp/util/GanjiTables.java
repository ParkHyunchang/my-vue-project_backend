package com.hyunchang.webapp.util;

import com.hyunchang.webapp.dto.SajuPillarDto;

/**
 * 60갑자(干支) 고정 테이블 — 천간·지지 한글/한자/오행 매핑, 그리고 시두법(五遁時訣) 계산.
 *
 * <p>년주·월주·일주는 KASI 음양력 정보 API가 내려준 간지 문자열을 파싱해 만들고, 시주만 이 클래스의 시두법 공식으로 로컬 계산한다.
 */
public final class GanjiTables {
    private GanjiTables() {}

    private static final String[] STEM_KR = {"갑", "을", "병", "정", "무", "기", "경", "신", "임", "계"};
    private static final String[] STEM_HANJA = {"甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸"};
    private static final String[] STEM_ELEMENT = {
        "목", "목", "화", "화", "토", "토", "금", "금", "수", "수"
    };

    private static final String[] BRANCH_KR = {
        "자", "축", "인", "묘", "진", "사", "오", "미", "신", "유", "술", "해"
    };
    private static final String[] BRANCH_HANJA = {
        "子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥"
    };
    private static final String[] BRANCH_ELEMENT = {
        "수", "토", "목", "목", "토", "화", "화", "토", "금", "금", "토", "수"
    };

    /** "경오" 또는 "경오(庚午)" 형태의 간지 문자열을 파싱해 Pillar 로 만든다. 한글 앞 두 글자를 천간·지지로 본다. */
    public static SajuPillarDto parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String trimmed = raw.trim();
        int parenIdx = trimmed.indexOf('(');
        String hangul = (parenIdx > 0 ? trimmed.substring(0, parenIdx) : trimmed).trim();
        if (hangul.length() < 2) return null;

        int stemIdx = indexOf(STEM_KR, String.valueOf(hangul.charAt(0)));
        int branchIdx = indexOf(BRANCH_KR, String.valueOf(hangul.charAt(1)));
        if (stemIdx < 0 || branchIdx < 0) return null;

        return buildPillar(stemIdx, branchIdx);
    }

    /** 일간(day stem) 한글 + 태어난 시각의 지지 인덱스로 시주를 계산한다 (오둔시결). */
    public static SajuPillarDto hourPillar(String dayStemKr, int hourBranchIdx) {
        int dayStemIdx = indexOf(STEM_KR, dayStemKr);
        if (dayStemIdx < 0 || hourBranchIdx < 0 || hourBranchIdx > 11) return null;
        int startStemIdx = (dayStemIdx % 5) * 2; // 오둔시결: 갑기→갑자, 을경→병자, 병신→무자, 정임→경자, 무계→임자
        int stemIdx = (startStemIdx + hourBranchIdx) % 10;
        return buildPillar(stemIdx, hourBranchIdx);
    }

    /** 태어난 시각(0~23시)을 12지지 시진 인덱스로 변환 (자시=23:00~00:59 기준). */
    public static int hourToBranchIndex(int hour) {
        return ((hour + 1) / 2) % 12;
    }

    public static String stemElementOf(String stemKr) {
        int idx = indexOf(STEM_KR, stemKr);
        return idx < 0 ? null : STEM_ELEMENT[idx];
    }

    public static String branchElementOf(String branchKr) {
        int idx = indexOf(BRANCH_KR, branchKr);
        return idx < 0 ? null : BRANCH_ELEMENT[idx];
    }

    private static SajuPillarDto buildPillar(int stemIdx, int branchIdx) {
        String stemKr = STEM_KR[stemIdx];
        String stemHanja = STEM_HANJA[stemIdx];
        String branchKr = BRANCH_KR[branchIdx];
        String branchHanja = BRANCH_HANJA[branchIdx];
        return SajuPillarDto.builder()
                .stemKr(stemKr)
                .stemHanja(stemHanja)
                .stemElement(STEM_ELEMENT[stemIdx])
                .branchKr(branchKr)
                .branchHanja(branchHanja)
                .branchElement(BRANCH_ELEMENT[branchIdx])
                .label(stemKr + branchKr + "(" + stemHanja + branchHanja + ")")
                .build();
    }

    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equals(v)) return i;
        }
        return -1;
    }
}
