package com.hyunchang.webapp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 사주 기둥(주) 하나 — 천간 + 지지 조합 (예: 년주, 월주, 일주, 시주). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SajuPillarDto {
    private String stemKr; // 천간 한글 (예: 경)
    private String stemHanja; // 천간 한자 (예: 庚)
    private String stemElement; // 천간 오행 (목/화/토/금/수)
    private String branchKr; // 지지 한글 (예: 오)
    private String branchHanja; // 지지 한자 (예: 午)
    private String branchElement; // 지지 오행 (목/화/토/금/수)
    private String label; // 한글+한자 조합 표기 (예: 경오(庚午))
}
