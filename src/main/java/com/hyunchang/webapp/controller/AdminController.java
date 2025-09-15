package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.dto.CreateUserRequest;
import com.hyunchang.webapp.dto.UpdateUserRoleRequest;
import com.hyunchang.webapp.dto.UserResponse;
import com.hyunchang.webapp.entity.Role;
import com.hyunchang.webapp.entity.User;
import com.hyunchang.webapp.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AdminController {
    
    private final UserService userService;
    
    // 관리자 권한 확인
    private boolean isAdmin(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        return userService.isAdmin(userDetails.getUsername());
    }
    
    // 모든 사용자 목록 조회
    @GetMapping("/users")
    public ResponseEntity<?> getAllUsers(Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body("관리자 권한이 필요합니다.");
        }
        
        try {
            List<User> users = userService.getAllUsers();
            List<UserResponse> userResponses = users.stream()
                    .map(UserResponse::from)
                    .collect(Collectors.toList());
            
            return ResponseEntity.ok(userResponses);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("사용자 목록 조회 실패: " + e.getMessage());
        }
    }
    
    // 특정 사용자 조회
    @GetMapping("/users/{id}")
    public ResponseEntity<?> getUserById(@PathVariable Long id, Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body("관리자 권한이 필요합니다.");
        }
        
        try {
            User user = userService.findById(id)
                    .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));
            
            return ResponseEntity.ok(UserResponse.from(user));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("사용자 조회 실패: " + e.getMessage());
        }
    }
    
    // 사용자 권한 수정
    @PutMapping("/users/{id}/role")
    public ResponseEntity<?> updateUserRole(
            @PathVariable Long id,
            @RequestBody UpdateUserRoleRequest request,
            Authentication authentication) {
        
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body("관리자 권한이 필요합니다.");
        }
        
        try {
            User updatedUser = userService.updateUserRole(id, request.getRole());
            return ResponseEntity.ok(UserResponse.from(updatedUser));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("권한 수정 실패: " + e.getMessage());
        }
    }
    
    // 사용자 삭제
    @DeleteMapping("/users/{id}")
    public ResponseEntity<?> deleteUser(@PathVariable Long id, Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body("관리자 권한이 필요합니다.");
        }
        
        try {
            userService.deleteUser(id);
            return ResponseEntity.ok("사용자가 성공적으로 삭제되었습니다.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("사용자 삭제 실패: " + e.getMessage());
        }
    }
    
    // 관리자 권한 확인
    @GetMapping("/check")
    public ResponseEntity<?> checkAdminRole(Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body("관리자 권한이 필요합니다.");
        }
        
        return ResponseEntity.ok("관리자 권한이 확인되었습니다.");
    }
    
    // 허용된 관리자 목록 조회 (관리자만 접근 가능)
    @GetMapping("/allowed-admins")
    public ResponseEntity<?> getAllowedAdmins(Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body("관리자 권한이 필요합니다.");
        }
        
        try {
            // UserService에서 허용된 관리자 목록을 가져오는 메서드가 필요
            List<String> allowedAdmins = userService.getAllowedAdminUsers();
            return ResponseEntity.ok(allowedAdmins);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("허용된 관리자 목록 조회 실패: " + e.getMessage());
        }
    }
    
    // 관리자가 새 사용자 생성 (토큰 없이)
    @PostMapping("/users")
    public ResponseEntity<?> createUser(@RequestBody CreateUserRequest request, Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body("관리자 권한이 필요합니다.");
        }
        
        try {
            if (request.getUserId() == null || request.getName() == null || request.getEmail() == null || 
                request.getPassword() == null || request.getRole() == null) {
                return ResponseEntity.badRequest().body("모든 필드를 입력해주세요.");
            }
            
            User user = userService.registerUser(
                request.getUserId(),
                request.getName(),
                request.getEmail(), 
                request.getPhone(),
                request.getPassword(), 
                request.getRole()
            );
            
            return ResponseEntity.ok(UserResponse.from(user));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("사용자 생성 실패: " + e.getMessage());
        }
    }
}
