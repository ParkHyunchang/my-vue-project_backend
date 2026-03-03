package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.config.JwtUtil;
import com.hyunchang.webapp.dto.AuthResponse;
import com.hyunchang.webapp.dto.FindIdRequest;
import com.hyunchang.webapp.dto.FindIdResponse;
import com.hyunchang.webapp.dto.LoginRequest;
import com.hyunchang.webapp.dto.RegisterRequest;
import com.hyunchang.webapp.dto.ResetPasswordRequest;
import com.hyunchang.webapp.entity.User;
import com.hyunchang.webapp.service.MenuDefinitionService;
import com.hyunchang.webapp.service.MenuPermissionService;
import com.hyunchang.webapp.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AuthController {
    
    private final AuthenticationManager authenticationManager;
    private final UserService userService;
    private final MenuPermissionService menuPermissionService;
    private final MenuDefinitionService menuDefinitionService;
    private final JwtUtil jwtUtil;
    
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@RequestBody RegisterRequest request) {
        try {
            User user = userService.registerUser(
                request.getUserId(),
                request.getName(),
                request.getEmail(),
                request.getPhone(),
                request.getPassword(),
                request.getRole()
            );
            
            UserDetails userDetails = userService.loadUserByUsername(user.getUsername());
            String token = jwtUtil.generateToken(userDetails);
            
            AuthResponse response = AuthResponse.builder()
                .token(token)
                .username(user.getUserId())
                .email(user.getEmail())
                .role(user.getRole())
                .message("회원가입이 완료되었습니다.")
                .build();
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            AuthResponse response = AuthResponse.builder()
                .message("회원가입 실패: " + e.getMessage())
                .build();
            return ResponseEntity.badRequest().body(response);
        }
    }
    
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody LoginRequest request) {
        try {
            // 먼저 사용자 존재 여부 확인
            if (!userService.existsByUserId(request.getUsername())) {
                AuthResponse response = AuthResponse.builder()
                    .message("로그인 실패: 존재하지 않는 아이디입니다.")
                    .build();
                return ResponseEntity.badRequest().body(response);
            }
            
            Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
            );
            
            UserDetails userDetails = (UserDetails) authentication.getPrincipal();
            String token = jwtUtil.generateToken(userDetails);
            
            User user = userService.findByUserId(userDetails.getUsername()).orElseThrow();
            
            AuthResponse response = AuthResponse.builder()
                .token(token)
                .username(user.getUserId())
                .email(user.getEmail())
                .role(user.getRole())
                .message("로그인이 완료되었습니다.")
                .build();
            
            return ResponseEntity.ok(response);
        } catch (org.springframework.security.authentication.BadCredentialsException e) {
            // 사용자는 존재하지만 비밀번호가 틀린 경우
            AuthResponse response = AuthResponse.builder()
                .message("로그인 실패: 비밀번호가 올바르지 않습니다.")
                .build();
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            AuthResponse response = AuthResponse.builder()
                .message("로그인 실패: " + e.getMessage())
                .build();
            return ResponseEntity.badRequest().body(response);
        }
    }
    
    @GetMapping("/me")
    public ResponseEntity<AuthResponse> getCurrentUser(Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated()) {
            UserDetails userDetails = (UserDetails) authentication.getPrincipal();
            User user = userService.findByUserId(userDetails.getUsername()).orElseThrow();
            
            AuthResponse response = AuthResponse.builder()
                .username(user.getUserId())
                .email(user.getEmail())
                .role(user.getRole())
                .message("현재 사용자 정보")
                .build();
            
            return ResponseEntity.ok(response);
        }
        
        return ResponseEntity.badRequest().body(
            AuthResponse.builder().message("인증되지 않은 사용자입니다.").build()
        );
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
            return ResponseEntity.badRequest().body(Map.of("available", false, "message", "아이디 확인 중 오류가 발생했습니다."));
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
            return ResponseEntity.badRequest().body(Map.of("available", false, "message", "이메일 확인 중 오류가 발생했습니다."));
        }
    }
    
    // 전체 메뉴 정의 조회 (인증된 사용자 누구나 가능 - 프론트엔드 store 초기화용)
    @GetMapping("/menu-definitions")
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
            return ResponseEntity.ok(menuPermissionService.getMenuPermissionsByRoleName(user.getRole()));
        } catch (Exception e) {
            System.out.println("사용자 메뉴 권한 조회 실패: " + e.getMessage());
            return ResponseEntity.badRequest().body("메뉴 권한 조회에 실패했습니다.");
        }
    }

    @PostMapping("/find-id")
    public ResponseEntity<?> findUserId(@RequestBody FindIdRequest request) {
        try {
            if (request == null || request.getName() == null || request.getEmail() == null) {
                return ResponseEntity.badRequest().body(Map.of("message", "이름과 이메일을 모두 입력해주세요."));
            }

            Optional<User> userOptional = userService.findByNameAndEmail(request.getName(), request.getEmail());
            if (userOptional.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("message", "일치하는 사용자 정보를 찾을 수 없습니다."));
            }

            User user = userOptional.get();
            String maskedUserId = maskUserId(user.getUserId());

            return ResponseEntity.ok(
                    FindIdResponse.builder()
                            .maskedUserId(maskedUserId)
                            .message("입력하신 정보와 일치하는 아이디를 찾았습니다.")
                            .build()
            );
        } catch (Exception e) {
            System.out.println("아이디 찾기 실패: " + e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("message", "아이디 찾기에 실패했습니다."));
        }
    }

    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody ResetPasswordRequest request) {
        try {
            if (request == null
                    || request.getUserId() == null
                    || request.getEmail() == null
                    || request.getPhone() == null
                    || request.getNewPassword() == null) {
                return ResponseEntity.badRequest().body(Map.of("message", "모든 정보를 입력해주세요."));
            }

            String trimmedPassword = request.getNewPassword().trim();
            if (trimmedPassword.length() < 8) {
                return ResponseEntity.badRequest().body(Map.of("message", "비밀번호는 8자 이상이어야 합니다."));
            }

            Optional<User> userOptional = userService.findByUserId(request.getUserId().trim());
            if (userOptional.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("message", "일치하는 사용자 정보를 찾을 수 없습니다."));
            }

            User user = userOptional.get();
            if (!user.getEmail().equalsIgnoreCase(request.getEmail().trim())) {
                return ResponseEntity.badRequest().body(Map.of("message", "이메일 정보가 일치하지 않습니다."));
            }

            String storedPhone = user.getPhone();
            String providedPhone = request.getPhone().trim();
            if (storedPhone == null || !normalizeDigits(storedPhone).equals(normalizeDigits(providedPhone))) {
                return ResponseEntity.badRequest().body(Map.of("message", "전화번호 정보가 일치하지 않습니다."));
            }

            userService.updatePassword(user, trimmedPassword);
            return ResponseEntity.ok(Map.of("message", "비밀번호가 성공적으로 변경되었습니다."));
        } catch (Exception e) {
            System.out.println("비밀번호 초기화 실패: " + e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("message", "비밀번호 초기화에 실패했습니다."));
        }
    }

    private String maskUserId(String userId) {
        if (userId == null || userId.length() <= 2) {
            return "****";
        }
        int visibleLength = Math.min(4, userId.length() - 2);
        String visiblePart = userId.substring(0, visibleLength);
        return visiblePart + "*".repeat(userId.length() - visibleLength);
    }

    private String normalizeDigits(String value) {
        if (value == null) {
            return "";
        }
        return Pattern.compile("[^0-9]").matcher(value).replaceAll("");
    }
}
