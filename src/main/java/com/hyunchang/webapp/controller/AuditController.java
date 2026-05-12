package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.util.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 감사 로그 전용 엔드포인트.
 * 프론트엔드가 "사용자가 메뉴/탭에 진입했다" 같은 데이터 호출이 없는 행동을 기록하기 위해 호출.
 * 비즈니스 로직 없이 로그 한 줄만 찍고 204를 반환한다.
 */
@Tag(name = "Audit", description = "사용자 행동 감사 로그")
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private static final Logger log = LoggerFactory.getLogger(AuditController.class);

    // 안전장치: 비정상 길이 입력 방지
    private static final int MAX_FIELD_LEN = 100;

    public record AuditEvent(String category, String action, String details) {}

    @Operation(summary = "감사 이벤트 기록 (메뉴 진입/탭 클릭 등)")
    @PostMapping("/event")
    public ResponseEntity<Void> recordEvent(@RequestBody AuditEvent event) {
        if (event == null) return ResponseEntity.noContent().build();

        String category = sanitize(event.category());
        String action   = sanitize(event.action());
        if (category.isBlank() || action.isBlank()) {
            return ResponseEntity.noContent().build();
        }

        String details = event.details() != null ? sanitize(event.details()) : "";

        log.info("[{}] user={}({}), {}{}",
            category,
            SecurityUtils.getCurrentUserId(),
            SecurityUtils.getCurrentUserRoleName(),
            action,
            details.isBlank() ? "" : " " + details);

        return ResponseEntity.noContent().build();
    }

    // 로그 인젝션 방지: 줄바꿈 제거 + 길이 제한
    private static String sanitize(String s) {
        if (s == null) return "";
        String cleaned = s.replaceAll("[\\r\\n]", " ").trim();
        if (cleaned.length() > MAX_FIELD_LEN) cleaned = cleaned.substring(0, MAX_FIELD_LEN);
        return cleaned;
    }
}
