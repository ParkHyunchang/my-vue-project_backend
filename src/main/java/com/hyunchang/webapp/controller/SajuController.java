package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.common.security.MenuAccessGuard;
import com.hyunchang.webapp.common.web.ApiResponses;
import com.hyunchang.webapp.dto.SajuAnalysisResponse;
import com.hyunchang.webapp.dto.SajuBirthInputDto;
import com.hyunchang.webapp.entity.SajuProfile;
import com.hyunchang.webapp.service.SajuAnalysisService;
import com.hyunchang.webapp.service.SajuProfileService;
import com.hyunchang.webapp.util.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Saju", description = "사주 API — 사주팔자 계산 + AI 해석")
@RestController
@RequestMapping("/api/saju")
public class SajuController {

    private static final String MENU_PATH = "/saju";
    private static final String INVALID_BIRTH_MSG = "생년월일이 필요합니다. 음력이면 연/월/일을 모두 입력하세요.";

    private final SajuAnalysisService sajuAnalysisService;
    private final SajuProfileService sajuProfileService;
    private final MenuAccessGuard menuAccessGuard;

    public SajuController(
            SajuAnalysisService sajuAnalysisService,
            SajuProfileService sajuProfileService,
            MenuAccessGuard menuAccessGuard) {
        this.sajuAnalysisService = sajuAnalysisService;
        this.sajuProfileService = sajuProfileService;
        this.menuAccessGuard = menuAccessGuard;
    }

    private boolean hasAccess() {
        return menuAccessGuard.hasAccess(MENU_PATH);
    }

    private ResponseEntity<?> forbidden() {
        return menuAccessGuard.forbidden();
    }

    // ── 요청 레코드 ────────────────────────────────────────────────

    /**
     * calendarType: "SOLAR"(기본) | "LUNAR". LUNAR 면 lunarYear/lunarMonth/lunarDay 필수, leapMonth 는 윤달
     * 여부.
     */
    public record BirthRequest(
            String label,
            String calendarType,
            String birthDate,
            Integer lunarYear,
            Integer lunarMonth,
            Integer lunarDay,
            Boolean leapMonth,
            String birthTime,
            Boolean timeUnknown) {}

    // ── 즉석 계산 (저장 없음) ────────────────────────────────────────

    @Operation(summary = "즉석 계산 — 저장 없이 사주팔자 계산 + AI 해석")
    @PostMapping("/calculate")
    public ResponseEntity<?> calculate(@RequestBody BirthRequest req) {
        if (!hasAccess()) return forbidden();
        SajuBirthInputDto input = buildInput(req);
        if (input == null) return ResponseEntity.badRequest().body(INVALID_BIRTH_MSG);
        SajuAnalysisResponse result = sajuAnalysisService.analyze(req.label(), input);
        return ResponseEntity.ok(result);
    }

    // ── 저장된 프로필 ────────────────────────────────────────────────

    @Operation(summary = "저장된 사주 프로필 전체 조회")
    @GetMapping("/profiles")
    public ResponseEntity<?> getProfiles() {
        if (!hasAccess()) return forbidden();
        List<SajuProfile> list = sajuProfileService.list(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(list);
    }

    @Operation(summary = "사주 프로필 저장 (계산 + AI 해석 포함)")
    @PostMapping("/profiles")
    public ResponseEntity<?> addProfile(@RequestBody BirthRequest req) {
        if (!hasAccess()) return forbidden();
        SajuBirthInputDto input = buildInput(req);
        if (input == null) return ResponseEntity.badRequest().body(INVALID_BIRTH_MSG);
        SajuAnalysisResponse result =
                sajuProfileService.create(SecurityUtils.getCurrentUserId(), req.label(), input);
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "사주 프로필 수정 (계산 + AI 재해석 포함)")
    @PutMapping("/profiles/{id}")
    public ResponseEntity<?> updateProfile(@PathVariable Long id, @RequestBody BirthRequest req) {
        if (!hasAccess()) return forbidden();
        SajuBirthInputDto input = buildInput(req);
        if (input == null) return ResponseEntity.badRequest().body(INVALID_BIRTH_MSG);
        SajuAnalysisResponse result =
                sajuProfileService.update(SecurityUtils.getCurrentUserId(), id, req.label(), input);
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "저장된 프로필 재해석 (AI 재호출)")
    @PostMapping("/profiles/{id}/reanalyze")
    public ResponseEntity<?> reanalyze(@PathVariable Long id) {
        if (!hasAccess()) return forbidden();
        SajuAnalysisResponse result =
                sajuProfileService.reanalyze(SecurityUtils.getCurrentUserId(), id);
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "사주 프로필 삭제")
    @DeleteMapping("/profiles/{id}")
    public ResponseEntity<?> deleteProfile(@PathVariable Long id) {
        if (!hasAccess()) return forbidden();
        sajuProfileService.delete(SecurityUtils.getCurrentUserId(), id);
        return ApiResponses.deletedMessage();
    }

    // ── 유틸 ──────────────────────────────────────────────────────

    /** 유효하지 않은 입력(필수 값 누락/파싱 실패)이면 null. */
    private SajuBirthInputDto buildInput(BirthRequest req) {
        if (req == null) return null;
        boolean timeUnknown = req.timeUnknown() != null && req.timeUnknown();
        LocalTime birthTime = timeUnknown ? null : toLocalTime(req.birthTime());
        boolean isLunar = "LUNAR".equalsIgnoreCase(req.calendarType());

        if (isLunar) {
            if (req.lunarYear() == null || req.lunarMonth() == null || req.lunarDay() == null) {
                return null;
            }
            return new SajuBirthInputDto(
                    "LUNAR",
                    null,
                    req.lunarYear(),
                    req.lunarMonth(),
                    req.lunarDay(),
                    req.leapMonth() != null && req.leapMonth(),
                    birthTime,
                    timeUnknown);
        }

        LocalDate birthDate = toLocalDate(req.birthDate());
        if (birthDate == null) return null;
        return new SajuBirthInputDto(
                "SOLAR", birthDate, null, null, null, false, birthTime, timeUnknown);
    }

    private LocalDate toLocalDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private LocalTime toLocalTime(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalTime.parse(s.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
