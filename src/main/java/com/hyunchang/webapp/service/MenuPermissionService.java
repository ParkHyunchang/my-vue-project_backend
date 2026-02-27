package com.hyunchang.webapp.service;

import com.hyunchang.webapp.entity.MenuPermission;
import com.hyunchang.webapp.entity.Role;
import com.hyunchang.webapp.repository.MenuPermissionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class MenuPermissionService {
    
    @Autowired
    private MenuPermissionRepository menuPermissionRepository;
    
    @PersistenceContext
    private EntityManager entityManager;
    
    /**
     * 모든 권한의 메뉴 권한 조회
     */
    public Map<String, List<String>> getAllMenuPermissions() {
        List<MenuPermission> permissions = menuPermissionRepository.findAll();
        Map<String, List<String>> result = new HashMap<>();
        
        // 각 역할별로 그룹화
        Map<Role, List<MenuPermission>> groupedByRole = permissions.stream()
            .collect(Collectors.groupingBy(MenuPermission::getRole));
        
        // 기본값 설정 (빈 리스트)
        result.put("USER", new ArrayList<>());
        result.put("PREMIUM", new ArrayList<>());
        result.put("ADMIN", new ArrayList<>());
        
        // 실제 권한 데이터 설정
        groupedByRole.forEach((role, perms) -> {
            List<String> menuPaths = perms.stream()
                .map(MenuPermission::getMenuPath)
                .collect(Collectors.toList());
            result.put(role.name(), menuPaths);
        });
        
        return result;
    }
    
    /**
     * 특정 권한의 메뉴 권한 조회
     */
    public List<String> getMenuPermissionsByRole(Role role) {
        List<MenuPermission> permissions = menuPermissionRepository.findByRole(role);
        return permissions.stream()
            .map(MenuPermission::getMenuPath)
            .collect(Collectors.toList());
    }
    
    /**
     * 사용자가 접근 가능한 메뉴 권한 조회 (상위 권한 포함)
     */
    public List<String> getAccessibleMenusForUser(Role userRole) {
        List<Role> applicableRoles = new ArrayList<>();
        
        // 사용자 권한에 따른 적용 가능한 권한들
        switch (userRole) {
            case ADMIN:
                applicableRoles.add(Role.ADMIN);
                applicableRoles.add(Role.PREMIUM);
                applicableRoles.add(Role.USER);
                break;
            case PREMIUM:
                applicableRoles.add(Role.PREMIUM);
                applicableRoles.add(Role.USER);
                break;
            case USER:
                applicableRoles.add(Role.USER);
                break;
        }
        
        List<MenuPermission> permissions = menuPermissionRepository.findByRoleIn(applicableRoles);
        return permissions.stream()
            .map(MenuPermission::getMenuPath)
            .distinct()
            .collect(Collectors.toList());
    }
    
    /**
     * 메뉴 권한 저장 (전체 권한 업데이트)
     */
    @Transactional
    public void saveMenuPermissions(Map<String, List<String>> permissions) {
        try {
            // 기존 권한 모두 삭제
            menuPermissionRepository.deleteAll();
            entityManager.flush(); // 즉시 DB에 반영
            entityManager.clear(); // 영속성 컨텍스트 초기화
            
            // 새로운 권한 저장
            List<MenuPermission> newPermissions = new ArrayList<>();
            
            permissions.forEach((roleName, menuPaths) -> {
                try {
                    Role role = Role.valueOf(roleName);
                    menuPaths.forEach(menuPath -> {
                        newPermissions.add(new MenuPermission(role, menuPath));
                    });
                } catch (IllegalArgumentException e) {
                    System.out.println("Invalid role: " + roleName);
                }
            });
            
            if (!newPermissions.isEmpty()) {
                menuPermissionRepository.saveAll(newPermissions);
            }
        } catch (Exception e) {
            System.out.println("Error saving menu permissions: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }
    
    /**
     * 특정 권한의 메뉴 권한 업데이트
     */
    @Transactional
    public void updateRoleMenuPermissions(Role role, List<String> menuPaths) {
        try {
            // 해당 권한의 기존 권한 삭제
            menuPermissionRepository.deleteByRole(role);
            
            // 새로운 권한 저장
            List<MenuPermission> newPermissions = menuPaths.stream()
                .map(menuPath -> new MenuPermission(role, menuPath))
                .collect(Collectors.toList());
            
            if (!newPermissions.isEmpty()) {
                menuPermissionRepository.saveAll(newPermissions);
            }
        } catch (Exception e) {
            System.out.println("Error updating role menu permissions: " + e.getMessage());
            throw e;
        }
    }
    
    /**
     * 사용자가 특정 메뉴에 접근할 수 있는지 확인
     */
    public boolean canAccessMenu(Role userRole, String menuPath) {
        List<String> accessibleMenus = getAccessibleMenusForUser(userRole);
        return accessibleMenus.contains(menuPath);
    }
    
    /**
     * 기본 메뉴 권한 초기화 (시스템 초기 설정용)
     * 기존 데이터가 있어도 누락된 경로만 추가하는 방식으로 동작
     */
    @Transactional
    public void initializeDefaultPermissions() {
        Map<String, List<String>> defaultPermissions = new HashMap<>();
        
        defaultPermissions.put("USER", Arrays.asList(
            "/", "/portfolio", "/projects", "/todos", "/todos/create"
        ));
        
        defaultPermissions.put("PREMIUM", Arrays.asList(
            "/", "/portfolio", "/projects", "/history", "/dating", "/dating_sys", "/todos", "/todos/create"
        ));
        
        defaultPermissions.put("ADMIN", Arrays.asList(
            "/", "/portfolio", "/projects", "/history", "/dating", "/dating_sys", "/todos", "/todos/create",
            "/expense", "/admin", "/admin/users", "/admin/menu-management", "/admin/role-management"
        ));

        // DB가 비어있으면 전체 저장, 데이터가 있으면 누락된 경로만 추가
        if (menuPermissionRepository.count() == 0) {
            saveMenuPermissions(defaultPermissions);
        } else {
            defaultPermissions.forEach((roleName, menuPaths) -> {
                try {
                    Role role = Role.valueOf(roleName);
                    List<String> existingPaths = menuPermissionRepository.findByRole(role)
                        .stream()
                        .map(MenuPermission::getMenuPath)
                        .collect(Collectors.toList());

                    List<MenuPermission> missing = menuPaths.stream()
                        .filter(path -> !existingPaths.contains(path))
                        .map(path -> new MenuPermission(role, path))
                        .collect(Collectors.toList());

                    if (!missing.isEmpty()) {
                        menuPermissionRepository.saveAll(missing);
                    }
                } catch (IllegalArgumentException e) {
                    System.out.println("Invalid role: " + roleName);
                }
            });
        }
    }
}
