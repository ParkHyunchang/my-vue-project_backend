package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.config.JwtUtil;
import com.hyunchang.webapp.dto.AuthResponse;
import com.hyunchang.webapp.dto.FindIdRequest;
import com.hyunchang.webapp.dto.LoginRequest;
import com.hyunchang.webapp.dto.RegisterRequest;
import com.hyunchang.webapp.dto.ResetPasswordRequest;
import com.hyunchang.webapp.entity.User;
import com.hyunchang.webapp.service.MenuDefinitionService;
import com.hyunchang.webapp.service.MenuPermissionService;
import com.hyunchang.webapp.service.TokenService;
import com.hyunchang.webapp.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthenticationManager authenticationManager;
    private final UserService userService;
    private final MenuPermissionService menuPermissionService;
    private final MenuDefinitionService menuDefinitionService;
    private final JwtUtil jwtUtil;
    private final TokenService tokenService;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(
            @RequestBody RegisterRequest request, HttpServletResponse httpResponse) {
        try {
            User user =
                    userService.registerUser(
                            request.getUserId(),
                            request.getName(),
                            request.getEmail(),
                            request.getPhone(),
                            request.getPassword(),
                            request.getRole());

            // httpOnly 쿠키로 access/refresh 토큰 발급
            tokenService.issueTokens(user.getUsername(), httpResponse);

            AuthResponse response =
                    AuthResponse.builder()
                            .username(user.getUserId())
                            .email(user.getEmail())
                            .role(user.getRole())
                            .message("회원가입이 완료되었습니다.")
                            .build();

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException
                | com.hyunchang.webapp.exception.UserAlreadyExistsException e) {
            // 사용자 입력에 기인한 예측 가능한 오류만 메시지를 그대로 노출
            AuthResponse response = AuthResponse.builder().message(e.getMessage()).build();
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            log.warn("회원가입 실패: userId={}", request.getUserId(), e);
            AuthResponse response = AuthResponse.builder().message("회원가입에 실패했습니다.").build();
            return ResponseEntity.badRequest().body(response);
        }
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        String ip = httpRequest.getRemoteAddr();
        // 계정 enumeration 방지: 사용자 존재/비밀번호 오류를 동일 메시지로 응답
        final String GENERIC_FAIL_MESSAGE = "로그인 실패: 아이디 또는 비밀번호가 올바르지 않습니다.";
        try {
            Authentication authentication =
                    authenticationManager.authenticate(
                            new UsernamePasswordAuthenticationToken(
                                    request.getUsername(), request.getPassword()));

            UserDetails userDetails = (UserDetails) authentication.getPrincipal();
            User user = userService.findByUserId(userDetails.getUsername()).orElseThrow();

            tokenService.issueTokens(userDetails.getUsername(), httpResponse);

            log.info(
                    "[LOGIN SUCCESS] user_id={}, role={}, ip={}",
                    user.getUserId(),
                    user.getRole(),
                    ip);

            AuthResponse response =
                    AuthResponse.builder()
                            .username(user.getUserId())
                            .email(user.getEmail())
                            .role(user.getRole())
                            .message("로그인이 완료되었습니다.")
                            .build();

            return ResponseEntity.ok(response);
        } catch (org.springframework.security.authentication.BadCredentialsException
                | org.springframework.security.core.userdetails.UsernameNotFoundException e) {
            log.warn(
                    "[LOGIN FAIL] reason=BAD_CREDENTIALS, user_id={}, ip={}",
                    request.getUsername(),
                    ip);
            return ResponseEntity.badRequest()
                    .body(AuthResponse.builder().message(GENERIC_FAIL_MESSAGE).build());
        } catch (Exception e) {
            log.warn("[LOGIN FAIL] reason=ERROR, user_id={}, ip={}", request.getUsername(), ip, e);
            return ResponseEntity.badRequest()
                    .body(AuthResponse.builder().message(GENERIC_FAIL_MESSAGE).build());
        }
    }

    /**
     * Refresh 엔드포인트: refresh_token 쿠키 검증 후 새 access_token 발급. refresh_token 자체는 회전하지 않음 (간단함 우선; 필요
     * 시 회전 추가).
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(
            HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        String refreshToken = tokenService.readCookie(httpRequest, TokenService.REFRESH_COOKIE);
        if (refreshToken == null || refreshToken.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("message", "refresh token이 없습니다."));
        }

        if (!jwtUtil.validateRefreshToken(refreshToken)) {
            tokenService.clearTokens(httpResponse);
            return ResponseEntity.status(401).body(Map.of("message", "refresh token이 유효하지 않습니다."));
        }

        String jti = jwtUtil.extractJti(refreshToken);
        if (tokenService.isRevoked(jti)) {
            tokenService.clearTokens(httpResponse);
            return ResponseEntity.status(401).body(Map.of("message", "refresh token이 무효화되었습니다."));
        }

        String username = jwtUtil.extractUsername(refreshToken);
        Optional<User> userOpt = userService.findByUserId(username);
        if (userOpt.isEmpty()) {
            tokenService.clearTokens(httpResponse);
            return ResponseEntity.status(401).body(Map.of("message", "사용자를 찾을 수 없습니다."));
        }

        tokenService.rotateAccessToken(username, httpResponse);
        return ResponseEntity.ok(Map.of("message", "토큰이 갱신되었습니다."));
    }

    /** Logout: access/refresh 쿠키 삭제 + 두 토큰의 jti를 블랙리스트에 등록. */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(
            HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        String accessToken = tokenService.readCookie(httpRequest, TokenService.ACCESS_COOKIE);
        String refreshToken = tokenService.readCookie(httpRequest, TokenService.REFRESH_COOKIE);
        tokenService.revoke(accessToken);
        tokenService.revoke(refreshToken);
        tokenService.clearTokens(httpResponse);
        return ResponseEntity.ok(Map.of("message", "로그아웃되었습니다."));
    }

    @GetMapping("/me")
    public ResponseEntity<AuthResponse> getCurrentUser(Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated()) {
            UserDetails userDetails = (UserDetails) authentication.getPrincipal();
            User user = userService.findByUserId(userDetails.getUsername()).orElseThrow();

            AuthResponse response =
                    AuthResponse.builder()
                            .username(user.getUserId())
                            .name(user.getName())
                            .email(user.getEmail())
                            .phone(user.getPhone())
                            .role(user.getRole())
                            .message("현재 사용자 정보")
                            .build();

            return ResponseEntity.ok(response);
        }

        // 미인증은 401로 반환해야 프론트 axios 인터셉터가 토큰 갱신·재시도를 수행한다.
        // (400이면 갱신 로직이 동작하지 않아 만료 시 세션 복구가 안 됨)
        return ResponseEntity.status(401)
                .body(AuthResponse.builder().message("인증되지 않은 사용자입니다.").build());
    }

    @PutMapping("/me")
    public ResponseEntity<?> updateProfile(
            @RequestBody com.hyunchang.webapp.dto.UpdateUserRequest request,
            Authentication authentication) {
        try {
            if (authentication == null || !authentication.isAuthenticated()) {
                return ResponseEntity.status(401).body(Map.of("message", "인증이 필요합니다."));
            }
            UserDetails userDetails = (UserDetails) authentication.getPrincipal();
            User user = userService.findByUserId(userDetails.getUsername()).orElseThrow();
            request.setRole(null);
            User updated = userService.updateUser(user.getId(), request);
            return ResponseEntity.ok(
                    AuthResponse.builder()
                            .username(updated.getUserId())
                            .name(updated.getName())
                            .email(updated.getEmail())
                            .phone(updated.getPhone())
                            .role(updated.getRole())
                            .message("정보가 수정되었습니다.")
                            .build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.warn("프로필 수정 실패", e);
            return ResponseEntity.badRequest().body(Map.of("message", "정보 수정에 실패했습니다."));
        }
    }

    @PutMapping("/change-password")
    public ResponseEntity<?> changePassword(
            @RequestBody Map<String, String> request, Authentication authentication) {
        try {
            if (authentication == null || !authentication.isAuthenticated()) {
                return ResponseEntity.status(401).body(Map.of("message", "인증이 필요합니다."));
            }
            String currentPassword = request.get("currentPassword");
            String newPassword = request.get("newPassword");
            if (currentPassword == null || newPassword == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("message", "현재 비밀번호와 새 비밀번호를 모두 입력해주세요."));
            }
            if (newPassword.trim().length() < 8) {
                return ResponseEntity.badRequest().body(Map.of("message", "새 비밀번호는 8자 이상이어야 합니다."));
            }
            UserDetails userDetails = (UserDetails) authentication.getPrincipal();
            User user = userService.findByUserId(userDetails.getUsername()).orElseThrow();
            userService.verifyAndChangePassword(user, currentPassword, newPassword.trim());
            return ResponseEntity.ok(Map.of("message", "비밀번호가 변경되었습니다."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.warn("비밀번호 변경 실패", e);
            return ResponseEntity.badRequest().body(Map.of("message", "비밀번호 변경에 실패했습니다."));
        }
    }

    @GetMapping("/check-username/{username}")
    public ResponseEntity<?> checkUsername(@PathVariable String username) {
        try {
            boolean exists = userService.existsByUserId(username);
            if (exists) {
                return ResponseEntity.ok(Map.of("available", false, "message", "이미 사용 중인 아이디입니다."));
            } else {
                return ResponseEntity.ok(Map.of("available", true, "message", "사용 가능한 아이디입니다."));
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("available", false, "message", "아이디 확인 중 오류가 발생했습니다."));
        }
    }

    @GetMapping("/check-email/{email}")
    public ResponseEntity<?> checkEmail(@PathVariable String email) {
        try {
            boolean exists = userService.existsByEmail(email);
            if (exists) {
                return ResponseEntity.ok(Map.of("available", false, "message", "이미 사용 중인 이메일입니다."));
            } else {
                return ResponseEntity.ok(Map.of("available", true, "message", "사용 가능한 이메일입니다."));
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("available", false, "message", "이메일 확인 중 오류가 발생했습니다."));
        }
    }

    // 전체 메뉴 정의 조회 (인증된 사용자 누구나 가능 - 프론트엔드 store 초기화용)
    @GetMapping("/menus")
    public ResponseEntity<?> getMenuDefinitions(Authentication authentication) {
        try {
            if (authentication == null || !authentication.isAuthenticated()) {
                return ResponseEntity.status(401).body("인증이 필요합니다.");
            }
            return ResponseEntity.ok(menuDefinitionService.getAllMenuDefinitions());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("메뉴 정의 조회에 실패했습니다.");
        }
    }

    // 현재 사용자의 메뉴 권한 조회 (인증된 사용자 누구나 가능)
    @GetMapping("/my-menu-permissions")
    public ResponseEntity<?> getMyMenuPermissions(Authentication authentication) {
        try {
            if (authentication == null || !authentication.isAuthenticated()) {
                return ResponseEntity.status(401).body("인증이 필요합니다.");
            }

            String username = authentication.getName();
            Optional<User> userOptional = userService.findByUserId(username);

            if (userOptional.isEmpty()) {
                return ResponseEntity.status(404).body("사용자를 찾을 수 없습니다.");
            }

            User user = userOptional.get();
            return ResponseEntity.ok(
                    menuPermissionService.getMenuPermissionsByRoleName(user.getRole()));
        } catch (Exception e) {
            log.warn("사용자 메뉴 권한 조회 실패", e);
            return ResponseEntity.badRequest().body("메뉴 권한 조회에 실패했습니다.");
        }
    }

    /**
     * 아이디 찾기 / 비밀번호 재설정 엔드포인트는 임시 비활성화. 사유: 이메일 OTP 인프라가 준비되기 전까지 (이름+이메일) 또는 (아이디+이메일+전화번호) 만으로
     * 비밀번호를 즉시 재설정하는 기존 흐름은 계정 탈취 primitive로 작동했음. 비밀번호 분실 시에는 관리자 콘솔의 reset 기능을 사용한다.
     */
    @PostMapping("/find-id")
    public ResponseEntity<?> findUserId(@RequestBody FindIdRequest request) {
        log.info("[DISABLED ENDPOINT] /api/auth/find-id 호출 차단");
        return ResponseEntity.status(410)
                .body(Map.of("message", "아이디 찾기 기능은 보안 강화를 위해 임시 비활성화되었습니다. 관리자에게 문의해주세요."));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody ResetPasswordRequest request) {
        log.info("[DISABLED ENDPOINT] /api/auth/reset-password 호출 차단");
        return ResponseEntity.status(410)
                .body(Map.of("message", "비밀번호 재설정 기능은 보안 강화를 위해 임시 비활성화되었습니다. 관리자에게 문의해주세요."));
    }
}
