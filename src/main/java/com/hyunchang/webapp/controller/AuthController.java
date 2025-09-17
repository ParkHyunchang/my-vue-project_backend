package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.config.JwtUtil;
import com.hyunchang.webapp.dto.AuthResponse;
import com.hyunchang.webapp.dto.LoginRequest;
import com.hyunchang.webapp.dto.RegisterRequest;
import com.hyunchang.webapp.entity.User;
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

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AuthController {
    
    private final AuthenticationManager authenticationManager;
    private final UserService userService;
    private final MenuPermissionService menuPermissionService;
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
            return ResponseEntity.ok(menuPermissionService.getMenuPermissionsByRole(user.getRole()));
        } catch (Exception e) {
            System.out.println("사용자 메뉴 권한 조회 실패: " + e.getMessage());
            return ResponseEntity.badRequest().body("메뉴 권한 조회에 실패했습니다.");
        }
    }
}
