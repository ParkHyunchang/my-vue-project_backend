package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.config.JwtUtil;
import com.hyunchang.webapp.dto.AuthResponse;
import com.hyunchang.webapp.dto.LoginRequest;
import com.hyunchang.webapp.dto.RegisterRequest;
import com.hyunchang.webapp.entity.User;
import com.hyunchang.webapp.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AuthController {
    
    private final AuthenticationManager authenticationManager;
    private final UserService userService;
    private final JwtUtil jwtUtil;
    
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@RequestBody RegisterRequest request) {
        try {
            User user = userService.registerUser(
                request.getUsername(),
                request.getEmail(),
                request.getPassword(),
                request.getRole()
            );
            
            UserDetails userDetails = userService.loadUserByUsername(user.getUsername());
            String token = jwtUtil.generateToken(userDetails);
            
            AuthResponse response = AuthResponse.builder()
                .token(token)
                .username(user.getUsername())
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
            Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
            );
            
            UserDetails userDetails = (UserDetails) authentication.getPrincipal();
            String token = jwtUtil.generateToken(userDetails);
            
            User user = userService.findByUsername(userDetails.getUsername()).orElseThrow();
            
            AuthResponse response = AuthResponse.builder()
                .token(token)
                .username(user.getUsername())
                .email(user.getEmail())
                .role(user.getRole())
                .message("로그인이 완료되었습니다.")
                .build();
            
            return ResponseEntity.ok(response);
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
            User user = userService.findByUsername(userDetails.getUsername()).orElseThrow();
            
            AuthResponse response = AuthResponse.builder()
                .username(user.getUsername())
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
}
