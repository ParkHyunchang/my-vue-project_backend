package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.dto.SajuAnalysisResponse;
import com.hyunchang.webapp.dto.SajuBirthInputDto;
import com.hyunchang.webapp.entity.SajuProfile;
import com.hyunchang.webapp.entity.User;
import com.hyunchang.webapp.repository.SajuProfileRepository;
import com.hyunchang.webapp.repository.UserRepository;
import com.hyunchang.webapp.util.SecurityUtils;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 사주 프로필 CRUD — 여러 명(나, 가족 등)의 생년월일시를 저장해두고 재조회/재해석한다. */
@Service
@Transactional
public class SajuProfileService {

    private static final Logger log = LoggerFactory.getLogger(SajuProfileService.class);

    private final SajuProfileRepository profileRepository;
    private final UserRepository userRepository;
    private final SajuAnalysisService sajuAnalysisService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SajuProfileService(
            SajuProfileRepository profileRepository,
            UserRepository userRepository,
            SajuAnalysisService sajuAnalysisService) {
        this.profileRepository = profileRepository;
        this.userRepository = userRepository;
        this.sajuAnalysisService = sajuAnalysisService;
    }

    private User requireUser(String userId) {
        return userRepository
                .findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("사용자를 찾을 수 없습니다: " + userId));
    }

    @Transactional(readOnly = true)
    public List<SajuProfile> list(String userId) {
        return profileRepository.findByUserUserIdOrderByCreatedAtDesc(userId);
    }

    /**
     * 계산 + AI 해석을 먼저 실행한 뒤 성공한 경우에만 프로필을 저장한다. 음력 입력은 환산된 양력 날짜(필수 컬럼)를 계산 전에는 알 수
     * 없으므로, 음력 환산 자체가 실패하면 프로필을 만들지 않고 실패 응답만 반환한다.
     */
    public SajuAnalysisResponse create(String userId, String label, SajuBirthInputDto input) {
        User user = requireUser(userId);
        SajuAnalysisResponse response = sajuAnalysisService.analyze(label, input);

        if (!response.isFound() && input.isLunar()) {
            return response;
        }

        SajuProfile profile = new SajuProfile();
        profile.setUser(user);
        LocalDate resolvedBirthDate =
                response.isFound() ? response.getPalja().getSolarBirthDate() : input.solarDate();
        applyInputToProfile(profile, label, input, resolvedBirthDate);
        applyAnalysisResult(profile, response);

        SajuProfile saved = profileRepository.save(profile);
        response.setProfileId(saved.getId());
        log.info(
                "[SAJU/PROFILE] user={}({}), CREATE id={} label={}",
                userId,
                SecurityUtils.getCurrentUserRoleName(),
                saved.getId(),
                saved.getLabel());
        return response;
    }

    public SajuAnalysisResponse update(String userId, Long id, String label, SajuBirthInputDto input) {
        SajuProfile profile =
                profileRepository
                        .findByIdAndUserUserId(id, userId)
                        .orElseThrow(() -> new IllegalArgumentException("사주 프로필을 찾을 수 없습니다."));
        SajuAnalysisResponse response = sajuAnalysisService.analyze(label, input);

        if (!response.isFound() && input.isLunar()) {
            // 음력 환산 실패 — 기존에 저장된 값을 잘못된 상태로 덮어쓰지 않는다.
            response.setProfileId(profile.getId());
            return response;
        }

        LocalDate resolvedBirthDate =
                response.isFound() ? response.getPalja().getSolarBirthDate() : input.solarDate();
        applyInputToProfile(profile, label, input, resolvedBirthDate);
        applyAnalysisResult(profile, response);

        profileRepository.save(profile);
        response.setProfileId(profile.getId());
        return response;
    }

    public SajuAnalysisResponse reanalyze(String userId, Long id) {
        SajuProfile profile =
                profileRepository
                        .findByIdAndUserUserId(id, userId)
                        .orElseThrow(() -> new IllegalArgumentException("사주 프로필을 찾을 수 없습니다."));
        SajuAnalysisResponse response = sajuAnalysisService.analyze(profile.getLabel(), toInput(profile));
        if (response.isFound()) {
            profile.setBirthDate(response.getPalja().getSolarBirthDate());
            applyAnalysisResult(profile, response);
            profileRepository.save(profile);
        }
        response.setProfileId(profile.getId());
        return response;
    }

    public void delete(String userId, Long id) {
        SajuProfile profile =
                profileRepository
                        .findByIdAndUserUserId(id, userId)
                        .orElseThrow(() -> new IllegalArgumentException("사주 프로필을 찾을 수 없습니다."));
        profileRepository.delete(profile);
        log.info(
                "[SAJU/PROFILE] user={}({}), DELETE id={} label={}",
                userId,
                SecurityUtils.getCurrentUserRoleName(),
                profile.getId(),
                profile.getLabel());
    }

    private void applyInputToProfile(
            SajuProfile profile, String label, SajuBirthInputDto input, LocalDate birthDate) {
        profile.setLabel(normalizeLabel(label));
        profile.setBirthDate(birthDate);
        profile.setTimeUnknown(input.timeUnknown());
        profile.setBirthTime(input.timeUnknown() ? null : input.birthTime());
        profile.setCalendarType(input.isLunar() ? "LUNAR" : "SOLAR");
        profile.setLunarYear(input.isLunar() ? input.lunarYear() : null);
        profile.setLunarMonth(input.isLunar() ? input.lunarMonth() : null);
        profile.setLunarDay(input.isLunar() ? input.lunarDay() : null);
        profile.setLeapMonth(input.isLunar() && input.leapMonth());
    }

    /** found=true 인 경우에만 palja/리포트를 반영한다(계산 자체가 실패하면 손댈 게 없음). */
    private void applyAnalysisResult(SajuProfile profile, SajuAnalysisResponse response) {
        if (!response.isFound()) return;
        profile.setPaljaJson(toJson(response.getPalja()));
        if (!response.isBlocked()) {
            profile.setLastReportMarkdown(response.getReport());
            profile.setAnalyzedAt(toLocalDateTime(response.getAnalyzedAt()));
        }
    }

    private SajuBirthInputDto toInput(SajuProfile profile) {
        if ("LUNAR".equalsIgnoreCase(profile.getCalendarType())) {
            return new SajuBirthInputDto(
                    "LUNAR",
                    null,
                    profile.getLunarYear(),
                    profile.getLunarMonth(),
                    profile.getLunarDay(),
                    profile.isLeapMonth(),
                    profile.getBirthTime(),
                    profile.isTimeUnknown());
        }
        return new SajuBirthInputDto(
                "SOLAR",
                profile.getBirthDate(),
                null,
                null,
                null,
                false,
                profile.getBirthTime(),
                profile.isTimeUnknown());
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    private String normalizeLabel(String label) {
        return (label == null || label.isBlank()) ? "나" : label.trim();
    }

    private String toJson(Object o) {
        if (o == null) return null;
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            log.warn("[SAJU/PROFILE] palja 직렬화 실패: {}", e.getMessage());
            return null;
        }
    }
}
