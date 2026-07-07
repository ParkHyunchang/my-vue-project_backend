package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.dto.AiPromptResponse;
import com.hyunchang.webapp.dto.AiPromptUpdateRequest;
import com.hyunchang.webapp.service.UserService;
import com.hyunchang.webapp.service.prompt.AiPromptService;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 프롬프트 관리 API (관리자 전용). GET /api/admin/prompts 전체 프롬프트 + 현재 상태 PUT /api/admin/prompts/{key}
 * 오버라이드 저장 (검증 후) POST /api/admin/prompts/{key}/reset 기본값으로 되돌리기
 */
@RestController
@RequestMapping("/api/admin/prompts")
@RequiredArgsConstructor
public class AdminPromptController {

    private static final Logger log = LoggerFactory.getLogger(AdminPromptController.class);

    private final AiPromptService aiPromptService;
    private final UserService userService;

    private boolean isAdmin(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        return userService.isAdmin(userDetails.getUsername());
    }

    @GetMapping
    public ResponseEntity<?> list(Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body("관리자 권한이 필요합니다.");
        }
        try {
            List<AiPromptResponse> result =
                    aiPromptService.getAdminViews().stream()
                            .map(AiPromptResponse::from)
                            .collect(Collectors.toList());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.warn("프롬프트 목록 조회 실패", e);
            return ResponseEntity.badRequest().body("프롬프트 목록 조회에 실패했습니다.");
        }
    }

    @PutMapping("/{key}")
    public ResponseEntity<?> update(
            @PathVariable String key,
            @RequestBody AiPromptUpdateRequest request,
            Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body("관리자 권한이 필요합니다.");
        }
        try {
            AiPromptService.PromptAdminView view =
                    aiPromptService.saveOverride(
                            key, request.getContent(), authentication.getName());
            log.info(
                    "[ADMIN/PROMPTS] user={}(ADMIN), SAVE key={} customized={}",
                    authentication.getName(),
                    key,
                    view.customized());
            return ResponseEntity.ok(AiPromptResponse.from(view));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            log.warn("프롬프트 저장 실패: key={}", key, e);
            return ResponseEntity.badRequest().body("프롬프트 저장에 실패했습니다.");
        }
    }

    @PostMapping("/{key}/reset")
    public ResponseEntity<?> reset(@PathVariable String key, Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body("관리자 권한이 필요합니다.");
        }
        try {
            AiPromptService.PromptAdminView view =
                    aiPromptService.resetOverride(key, authentication.getName());
            log.info("[ADMIN/PROMPTS] user={}(ADMIN), RESET key={}", authentication.getName(), key);
            return ResponseEntity.ok(AiPromptResponse.from(view));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            log.warn("프롬프트 초기화 실패: key={}", key, e);
            return ResponseEntity.badRequest().body("프롬프트 초기화에 실패했습니다.");
        }
    }
}
