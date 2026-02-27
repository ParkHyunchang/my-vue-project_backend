package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.dto.CreateUserRequest;
import com.hyunchang.webapp.dto.MenuPermissionRequest;
import com.hyunchang.webapp.dto.RoleInfoRequest;
import com.hyunchang.webapp.dto.RoleInfoResponse;
import com.hyunchang.webapp.dto.UpdateUserRequest;
import com.hyunchang.webapp.dto.UpdateUserRoleRequest;
import com.hyunchang.webapp.dto.UserResponse;
import com.hyunchang.webapp.entity.User;
import com.hyunchang.webapp.service.MenuPermissionService;
import com.hyunchang.webapp.service.MenuCrudPermissionService;
import com.hyunchang.webapp.service.RoleInfoService;
import com.hyunchang.webapp.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AdminController {
    
    private final UserService userService;
    private final MenuPermissionService menuPermissionService;
    private final MenuCrudPermissionService menuCrudPermissionService;
    private final RoleInfoService roleInfoService;
    
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
    
    // 사용자 정보 전체 수정
    @PutMapping("/users/{id}")
    public ResponseEntity<?> updateUser(
            @PathVariable Long id,
            @RequestBody UpdateUserRequest request,
            Authentication authentication) {
        
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body("관리자 권한이 필요합니다.");
        }
        
        try {
            User updatedUser = userService.updateUser(id, request);
            return ResponseEntity.ok(UserResponse.from(updatedUser));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("사용자 정보 수정 실패: " + e.getMessage());
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
    
    // 메뉴 권한 조회
    @GetMapping("/menu-permissions")
    public ResponseEntity<?> getMenuPermissions(Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body("관리자 권한이 필요합니다.");
        }
        
        try {
            return ResponseEntity.ok(menuPermissionService.getAllMenuPermissions());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("메뉴 권한 조회에 실패했습니다.");
        }
    }
    
    // 메뉴 권한 저장
    @PostMapping("/menu-permissions")
    public ResponseEntity<?> saveMenuPermissions(
            @RequestBody MenuPermissionRequest request,
            Authentication authentication) {
        
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body("관리자 권한이 필요합니다.");
        }
        
        try {
            if (request.getPermissions() == null) {
                return ResponseEntity.badRequest().body("권한 정보가 필요합니다.");
            }
            
            menuPermissionService.saveMenuPermissions(request.getPermissions());
            return ResponseEntity.ok("메뉴 권한이 성공적으로 저장되었습니다.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("메뉴 권한 저장에 실패했습니다.");
        }
    }
    
    // 특정 권한의 메뉴 권한 조회 (관리자 전용)
    @GetMapping("/menu-permissions/{role}")
    public ResponseEntity<?> getMenuPermissionsByRole(
            @PathVariable String role,
            Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body("관리자 권한이 필요합니다.");
        }
        try {
            return ResponseEntity.ok(menuPermissionService.getMenuPermissionsByRoleName(role.toUpperCase()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("메뉴 권한 조회에 실패했습니다.");
        }
    }
    
    // CRUD 권한 초기화
    @PostMapping("/crud-permissions/initialize")
    public ResponseEntity<?> initializeCrudPermissions(Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body("관리자 권한이 필요합니다.");
        }
        try {
            menuCrudPermissionService.initializeDefaultCrudPermissions();
            return ResponseEntity.ok("기본 CRUD 권한이 초기화되었습니다.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("CRUD 권한 초기화에 실패했습니다.");
        }
    }

    // 사용자 본인의 CRUD 권한 조회 (로그인된 사용자 권한 기반)
    @GetMapping("/user-crud-permissions")
    public ResponseEntity<?> getUserCrudPermissions(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(401).body("인증이 필요합니다.");
        }
        try {
            UserDetails userDetails = (UserDetails) authentication.getPrincipal();
            User user = userService.findByUserId(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));
            return ResponseEntity.ok(menuCrudPermissionService.getPermissionsByRoleName(user.getRole()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("CRUD 권한 조회에 실패했습니다.");
        }
    }

    // 특정 권한의 CRUD 권한 조회
    @GetMapping("/crud-permissions/{role}")
    public ResponseEntity<?> getCrudPermissionsByRole(
            @PathVariable String role,
            Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body("관리자 권한이 필요합니다.");
        }
        try {
            return ResponseEntity.ok(menuCrudPermissionService.getPermissionsByRoleName(role.toUpperCase()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("CRUD 권한 조회에 실패했습니다.");
        }
    }

    // 특정 권한의 CRUD 권한 저장 (권한별 전체 교체)
    @PostMapping("/crud-permissions/{role}")
    public ResponseEntity<?> saveCrudPermissionsByRole(
            @PathVariable String role,
            @RequestBody Map<String, Map<String, Boolean>> permissionsData,
            Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body("관리자 권한이 필요합니다.");
        }
        try {
            menuCrudPermissionService.saveRoleCrudPermissions(role.toUpperCase(), permissionsData);
            return ResponseEntity.ok("CRUD 권한이 저장되었습니다.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("CRUD 권한 저장에 실패했습니다: " + e.getMessage());
        }
    }
    
    // ===== 권한(Role) 관리 API =====

    // 모든 권한 정보 조회
    @GetMapping("/role-infos")
    public ResponseEntity<?> getAllRoleInfos(Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body("관리자 권한이 필요합니다.");
        }
        try {
            return ResponseEntity.ok(roleInfoService.getAllRoleInfos());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("권한 정보 조회에 실패했습니다.");
        }
    }

    // 권한 정보 생성
    @PostMapping("/role-infos")
    public ResponseEntity<?> createRoleInfo(
            @RequestBody RoleInfoRequest request,
            Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body("관리자 권한이 필요합니다.");
        }
        try {
            RoleInfoResponse created = roleInfoService.createRoleInfo(request);
            return ResponseEntity.ok(created);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("권한 생성에 실패했습니다: " + e.getMessage());
        }
    }

    // 특정 권한 정보 수정 (표시명, 설명)
    @PutMapping("/role-infos/{id}")
    public ResponseEntity<?> updateRoleInfo(
            @PathVariable Long id,
            @RequestBody RoleInfoRequest request,
            Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body("관리자 권한이 필요합니다.");
        }
        try {
            RoleInfoResponse updated = roleInfoService.updateRoleInfo(id, request);
            return ResponseEntity.ok(updated);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("권한 정보 수정에 실패했습니다: " + e.getMessage());
        }
    }

    // 권한 삭제 (기본 권한 보호)
    @DeleteMapping("/role-infos/{id}")
    public ResponseEntity<?> deleteRoleInfo(
            @PathVariable Long id,
            Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body("관리자 권한이 필요합니다.");
        }
        try {
            roleInfoService.deleteRoleInfo(id);
            return ResponseEntity.ok("권한이 삭제되었습니다.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("권한 삭제에 실패했습니다: " + e.getMessage());
        }
    }

    // 특정 권한을 가진 사용자 목록 조회
    @GetMapping("/role-infos/{roleName}/users")
    public ResponseEntity<?> getUsersByRole(
            @PathVariable String roleName,
            Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body("관리자 권한이 필요합니다.");
        }
        try {
            RoleInfoResponse roleInfo = roleInfoService.getRoleInfoByRoleName(roleName.toUpperCase());
            List<UserResponse> users = userService.getUsersByRole(roleName.toUpperCase()).stream()
                    .map(UserResponse::from)
                    .collect(Collectors.toList());
            return ResponseEntity.ok(Map.of("roleInfo", roleInfo, "users", users));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("사용자 목록 조회에 실패했습니다.");
        }
    }

    // 권한 초기화 (기본 데이터 없을 때 수동 초기화)
    @PostMapping("/role-infos/initialize")
    public ResponseEntity<?> initializeRoleInfos(Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body("관리자 권한이 필요합니다.");
        }
        try {
            roleInfoService.initializeDefaultRoles();
            return ResponseEntity.ok("권한 정보가 초기화되었습니다.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("권한 초기화에 실패했습니다.");
        }
    }

}
