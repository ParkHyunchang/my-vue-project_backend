package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.dto.CreateUserRequest;
import com.hyunchang.webapp.dto.MenuDefinitionRequest;
import com.hyunchang.webapp.dto.MenuDefinitionResponse;
import com.hyunchang.webapp.dto.MenuPermissionRequest;
import com.hyunchang.webapp.dto.MenuSortOrderRequest;
import com.hyunchang.webapp.dto.RoleInfoRequest;
import com.hyunchang.webapp.dto.RoleInfoResponse;
import com.hyunchang.webapp.dto.UpdateUserRequest;
import com.hyunchang.webapp.dto.UserResponse;
import com.hyunchang.webapp.entity.User;
import com.hyunchang.webapp.service.MenuDefinitionService;
import com.hyunchang.webapp.service.MenuPermissionService;
import com.hyunchang.webapp.service.RoleInfoService;
import com.hyunchang.webapp.service.UserService;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final UserService userService;
    private final MenuPermissionService menuPermissionService;
    private final RoleInfoService roleInfoService;
    private final MenuDefinitionService menuDefinitionService;

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
            List<UserResponse> userResponses =
                    users.stream().map(UserResponse::from).collect(Collectors.toList());

            return ResponseEntity.ok(userResponses);
        } catch (Exception e) {
            log.warn("사용자 목록 조회 실패", e);
            return ResponseEntity.badRequest().body("사용자 목록 조회에 실패했습니다.");
        }
    }

    // 특정 사용자 조회
    @GetMapping("/users/{id}")
    public ResponseEntity<?> getUserById(@PathVariable Long id, Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body("관리자 권한이 필요합니다.");
        }

        try {
            User user =
                    userService
                            .findById(id)
                            .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

            return ResponseEntity.ok(UserResponse.from(user));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            log.warn("사용자 조회 실패: id={}", id, e);
            return ResponseEntity.badRequest().body("사용자 조회에 실패했습니다.");
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
            log.info("[ADMIN/USERS] user={}(ADMIN), UPDATE id={}", authentication.getName(), id);
            return ResponseEntity.ok(UserResponse.from(updatedUser));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            log.warn("사용자 정보 수정 실패: id={}", id, e);
            return ResponseEntity.badRequest().body("사용자 정보 수정에 실패했습니다.");
        }
    }

    // 사용자 삭제
    @DeleteMapping("/users/{id}")
    public ResponseEntity<?> deleteUser(@PathVariable Long id, Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body("관리자 권한이 필요합니다.");
        }

        try {
            User targetUser = userService.findById(id).orElse(null);
            String targetUserId = targetUser != null ? targetUser.getUserId() : "id=" + id;

            if (targetUser != null && "ADMIN".equals(targetUser.getRole())) {
                return ResponseEntity.badRequest().body("관리자 계정은 삭제할 수 없습니다.");
            }

            userService.deleteUser(id);
            log.info(
                    "[ADMIN/USERS] user={}(ADMIN), DELETE id={} user_id={}",
                    authentication.getName(),
                    id,
                    targetUserId);
            return ResponseEntity.ok("사용자가 성공적으로 삭제되었습니다.");
        } catch (Exception e) {
            log.warn("사용자 삭제 실패: id={}", id, e);
            return ResponseEntity.badRequest().body("사용자 삭제에 실패했습니다.");
        }
    }

    // 관리자가 사용자 비밀번호 초기화
    @PutMapping("/users/{id}/password")
    public ResponseEntity<?> resetUserPassword(
            @PathVariable Long id,
            @RequestBody Map<String, String> request,
            Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body("관리자 권한이 필요합니다.");
        }
        try {
            String newPassword = request.get("password");
            if (newPassword == null || newPassword.length() < 6) {
                return ResponseEntity.badRequest().body("비밀번호는 최소 6자 이상이어야 합니다.");
            }
            User user =
                    userService
                            .findById(id)
                            .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
            userService.updatePassword(user, newPassword);
            log.info(
                    "[ADMIN/USERS] user={}(ADMIN), RESET_PASSWORD id={}",
                    authentication.getName(),
                    id);
            return ResponseEntity.ok("비밀번호가 성공적으로 변경되었습니다.");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            log.warn("비밀번호 변경 실패: id={}", id, e);
            return ResponseEntity.badRequest().body("비밀번호 변경에 실패했습니다.");
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
            log.warn("허용된 관리자 목록 조회 실패", e);
            return ResponseEntity.badRequest().body("허용된 관리자 목록 조회에 실패했습니다.");
        }
    }

    // 관리자가 새 사용자 생성 (토큰 없이)
    @PostMapping("/users")
    public ResponseEntity<?> createUser(
            @RequestBody CreateUserRequest request, Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body("관리자 권한이 필요합니다.");
        }

        try {
            if (request.getUserId() == null
                    || request.getName() == null
                    || request.getEmail() == null
                    || request.getPassword() == null
                    || request.getRole() == null) {
                return ResponseEntity.badRequest().body("모든 필드를 입력해주세요.");
            }

            User user =
                    userService.registerUser(
                            request.getUserId(),
                            request.getName(),
                            request.getEmail(),
                            request.getPhone(),
                            request.getPassword(),
                            request.getRole());
            log.info(
                    "[ADMIN/USERS] user={}(ADMIN), CREATE id={} user_id={} role={}",
                    authentication.getName(),
                    user.getId(),
                    request.getUserId(),
                    request.getRole());
            return ResponseEntity.ok(UserResponse.from(user));
        } catch (IllegalArgumentException
                | com.hyunchang.webapp.exception.UserAlreadyExistsException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            log.warn("사용자 생성 실패: userId={}", request.getUserId(), e);
            return ResponseEntity.badRequest().body("사용자 생성에 실패했습니다.");
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
            @RequestBody MenuPermissionRequest request, Authentication authentication) {

        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body("관리자 권한이 필요합니다.");
        }

        try {
            if (request.getPermissions() == null) {
                return ResponseEntity.badRequest().body("권한 정보가 필요합니다.");
            }

            menuPermissionService.saveMenuPermissions(request.getPermissions());
            log.info("[ADMIN/MENU-PERMS] user={}(ADMIN), SAVE", authentication.getName());
            return ResponseEntity.ok("메뉴 권한이 성공적으로 저장되었습니다.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("메뉴 권한 저장에 실패했습니다.");
        }
    }

    // 특정 권한의 메뉴 권한 조회 (관리자 전용)
    @GetMapping("/menu-permissions/{role}")
    public ResponseEntity<?> getMenuPermissionsByRole(
            @PathVariable String role, Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body("관리자 권한이 필요합니다.");
        }
        try {
            return ResponseEntity.ok(
                    menuPermissionService.getMenuPermissionsByRoleName(role.toUpperCase()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("메뉴 권한 조회에 실패했습니다.");
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
            @RequestBody RoleInfoRequest request, Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body("관리자 권한이 필요합니다.");
        }
        try {
            RoleInfoResponse created = roleInfoService.createRoleInfo(request);
            log.info(
                    "[ADMIN/ROLES] user={}(ADMIN), CREATE id={} role_name={}",
                    authentication.getName(),
                    created.getId(),
                    request.getRoleName());
            return ResponseEntity.ok(created);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            log.warn("권한 생성 실패: role_name={}", request.getRoleName(), e);
            return ResponseEntity.badRequest().body("권한 생성에 실패했습니다.");
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
            log.info("[ADMIN/ROLES] user={}(ADMIN), UPDATE id={}", authentication.getName(), id);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            log.warn("권한 정보 수정 실패: id={}", id, e);
            return ResponseEntity.badRequest().body("권한 정보 수정에 실패했습니다.");
        }
    }

    // 권한 삭제 (기본 권한 보호)
    @DeleteMapping("/role-infos/{id}")
    public ResponseEntity<?> deleteRoleInfo(@PathVariable Long id, Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body("관리자 권한이 필요합니다.");
        }
        try {
            roleInfoService.deleteRoleInfo(id);
            log.info("[ADMIN/ROLES] user={}(ADMIN), DELETE id={}", authentication.getName(), id);
            return ResponseEntity.ok("권한이 삭제되었습니다.");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            log.warn("권한 삭제 실패: id={}", id, e);
            return ResponseEntity.badRequest().body("권한 삭제에 실패했습니다.");
        }
    }

    // 특정 권한을 가진 사용자 목록 조회
    @GetMapping("/role-infos/{roleName}/users")
    public ResponseEntity<?> getUsersByRole(
            @PathVariable String roleName, Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body("관리자 권한이 필요합니다.");
        }
        try {
            RoleInfoResponse roleInfo =
                    roleInfoService.getRoleInfoByRoleName(roleName.toUpperCase());
            List<UserResponse> users =
                    userService.getUsersByRole(roleName.toUpperCase()).stream()
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
            log.info("[ADMIN/ROLES] user={}(ADMIN), INIT", authentication.getName());
            return ResponseEntity.ok("권한 정보가 초기화되었습니다.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("권한 초기화에 실패했습니다.");
        }
    }

    // ===== 메뉴 정의 관리 API =====

    // 전체 메뉴 정의 조회 (관리자 전용)
    @GetMapping("/menus")
    public ResponseEntity<?> getAllMenuDefinitions(Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body("관리자 권한이 필요합니다.");
        }
        try {
            return ResponseEntity.ok(menuDefinitionService.getAllMenuDefinitions());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("메뉴 정의 조회에 실패했습니다.");
        }
    }

    // 메뉴 정의 생성
    @PostMapping("/menus")
    public ResponseEntity<?> createMenuDefinition(
            @RequestBody MenuDefinitionRequest request, Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body("관리자 권한이 필요합니다.");
        }
        try {
            MenuDefinitionResponse created = menuDefinitionService.createMenuDefinition(request);
            log.info(
                    "[ADMIN/MENUS] user={}(ADMIN), CREATE id={} path={} name={}",
                    authentication.getName(),
                    created.getId(),
                    request.getPath(),
                    request.getName());
            return ResponseEntity.ok(created);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            log.warn("메뉴 생성 실패: path={}", request.getPath(), e);
            return ResponseEntity.badRequest().body("메뉴 생성에 실패했습니다.");
        }
    }

    // 메뉴 정의 수정
    @PutMapping("/menus/{id}")
    public ResponseEntity<?> updateMenuDefinition(
            @PathVariable Long id,
            @RequestBody MenuDefinitionRequest request,
            Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body("관리자 권한이 필요합니다.");
        }
        try {
            MenuDefinitionResponse updated =
                    menuDefinitionService.updateMenuDefinition(id, request);
            log.info(
                    "[ADMIN/MENUS] user={}(ADMIN), UPDATE id={} name={}",
                    authentication.getName(),
                    id,
                    request.getName());
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            log.warn("메뉴 수정 실패: id={}", id, e);
            return ResponseEntity.badRequest().body("메뉴 수정에 실패했습니다.");
        }
    }

    // 메뉴 순서 일괄 업데이트
    @PutMapping("/menus/sort-order")
    public ResponseEntity<?> updateMenuSortOrders(
            @RequestBody List<MenuSortOrderRequest> requests, Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body("관리자 권한이 필요합니다.");
        }
        try {
            menuDefinitionService.updateSortOrders(requests);
            log.info(
                    "[ADMIN/MENUS] user={}(ADMIN), UPDATE_SORT_ORDERS count={}",
                    authentication.getName(),
                    requests.size());
            return ResponseEntity.ok("메뉴 순서가 업데이트되었습니다.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("메뉴 순서 업데이트에 실패했습니다.");
        }
    }

    // 메뉴 정의 삭제
    @DeleteMapping("/menus/{id}")
    public ResponseEntity<?> deleteMenuDefinition(
            @PathVariable Long id, Authentication authentication) {
        if (!isAdmin(authentication)) {
            return ResponseEntity.status(403).body("관리자 권한이 필요합니다.");
        }
        try {
            menuDefinitionService.deleteMenuDefinition(id);
            log.info("[ADMIN/MENUS] user={}(ADMIN), DELETE id={}", authentication.getName(), id);
            return ResponseEntity.ok("메뉴가 삭제되었습니다.");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            log.warn("메뉴 삭제 실패: id={}", id, e);
            return ResponseEntity.badRequest().body("메뉴 삭제에 실패했습니다.");
        }
    }
}
