package com.hyunchang.webapp.service;

import com.hyunchang.webapp.entity.MenuCrudPermission;
import com.hyunchang.webapp.entity.Role;
import com.hyunchang.webapp.repository.MenuCrudPermissionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.util.*;

@Service
public class MenuCrudPermissionService {
    
    @Autowired
    private MenuCrudPermissionRepository menuCrudPermissionRepository;
    
    @PersistenceContext
    private EntityManager entityManager;
    
    /**
     * 사용자가 특정 메뉴에서 특정 CRUD 작업을 수행할 수 있는지 확인
     */
    public boolean hasPermission(Role userRole, String menuPath, String operation) {
        // 관리자는 모든 권한을 가짐
        if (userRole == Role.ADMIN) {
            return true;
        }
        
        // 특정 권한 확인
        Optional<MenuCrudPermission> permission = menuCrudPermissionRepository
            .findByRoleAndMenuPath(userRole, menuPath);
        
        if (permission.isEmpty()) {
            return false;
        }
        
        MenuCrudPermission perm = permission.get();
        switch (operation.toUpperCase()) {
            case "CREATE":
                return perm.getCanCreate();
            case "READ":
                return perm.getCanRead();
            case "UPDATE":
                return perm.getCanUpdate();
            case "DELETE":
                return perm.getCanDelete();
            default:
                return false;
        }
    }
    
    /**
     * 사용자가 특정 메뉴에서 삭제 권한이 있는지 확인
     */
    public boolean canDelete(Role userRole, String menuPath) {
        return hasPermission(userRole, menuPath, "DELETE");
    }
    
    /**
     * 사용자가 특정 메뉴에서 생성 권한이 있는지 확인
     */
    public boolean canCreate(Role userRole, String menuPath) {
        return hasPermission(userRole, menuPath, "CREATE");
    }
    
    /**
     * 사용자가 특정 메뉴에서 수정 권한이 있는지 확인
     */
    public boolean canUpdate(Role userRole, String menuPath) {
        return hasPermission(userRole, menuPath, "UPDATE");
    }
    
    /**
     * 사용자가 특정 메뉴에서 조회 권한이 있는지 확인
     */
    public boolean canRead(Role userRole, String menuPath) {
        return hasPermission(userRole, menuPath, "READ");
    }
    
    /**
     * 특정 권한의 모든 메뉴 CRUD 권한 조회
     */
    public List<MenuCrudPermission> getPermissionsByRole(Role role) {
        return menuCrudPermissionRepository.findByRole(role);
    }
    
    /**
     * 특정 메뉴의 모든 권한별 CRUD 권한 조회
     */
    public List<MenuCrudPermission> getPermissionsByMenu(String menuPath) {
        return menuCrudPermissionRepository.findByMenuPath(menuPath);
    }
    
    /**
     * 모든 CRUD 권한 조회 (권한별로 그룹화)
     */
    public Map<String, List<MenuCrudPermission>> getAllCrudPermissions() {
        List<MenuCrudPermission> permissions = menuCrudPermissionRepository.findAll();
        Map<String, List<MenuCrudPermission>> result = new HashMap<>();
        
        // 각 역할별로 그룹화
        Map<Role, List<MenuCrudPermission>> groupedByRole = permissions.stream()
            .collect(java.util.stream.Collectors.groupingBy(MenuCrudPermission::getRole));
        
        // 기본값 설정 (빈 리스트)
        result.put("USER", new ArrayList<>());
        result.put("PREMIUM", new ArrayList<>());
        result.put("ADMIN", new ArrayList<>());
        
        // 실제 권한 데이터 설정
        groupedByRole.forEach((role, perms) -> {
            result.put(role.name(), perms);
        });
        
        return result;
    }
    
    /**
     * CRUD 권한 저장 (전체 권한 업데이트)
     */
    @Transactional
    public void saveCrudPermissions(Map<String, List<Map<String, Object>>> permissions) {
        try {
            // 기존 권한 모두 삭제
            menuCrudPermissionRepository.deleteAll();
            entityManager.flush(); // 즉시 DB에 반영
            entityManager.clear(); // 영속성 컨텍스트 초기화
            
            // 새로운 권한 저장
            List<MenuCrudPermission> newPermissions = new ArrayList<>();
            
            permissions.forEach((roleName, menuPermissions) -> {
                try {
                    Role role = Role.valueOf(roleName);
                    menuPermissions.forEach(menuPerm -> {
                        String menuPath = (String) menuPerm.get("menuPath");
                        Boolean canCreate = (Boolean) menuPerm.get("canCreate");
                        Boolean canRead = (Boolean) menuPerm.get("canRead");
                        Boolean canUpdate = (Boolean) menuPerm.get("canUpdate");
                        Boolean canDelete = (Boolean) menuPerm.get("canDelete");
                        
                        newPermissions.add(new MenuCrudPermission(
                            role, menuPath, canCreate, canRead, canUpdate, canDelete
                        ));
                    });
                } catch (IllegalArgumentException e) {
                    System.out.println("Invalid role: " + roleName);
                }
            });
            
            if (!newPermissions.isEmpty()) {
                menuCrudPermissionRepository.saveAll(newPermissions);
            }
        } catch (Exception e) {
            System.out.println("Error saving CRUD permissions: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }
    
    /**
     * 기본 CRUD 권한 초기화 (시스템 초기 설정용)
     */
    @Transactional
    public void initializeDefaultCrudPermissions() {
        // 기존 권한이 있는지 확인
        if (menuCrudPermissionRepository.count() > 0) {
            return; // 이미 권한이 설정되어 있으면 초기화하지 않음
        }
        
        Map<String, List<Map<String, Object>>> defaultPermissions = new HashMap<>();
        
        // 일반 사용자 권한 (모든 메뉴에서 조회만 가능)
        List<Map<String, Object>> userPermissions = Arrays.asList(
            createPermissionMap("/", false, true, false, false),
            createPermissionMap("/portfolio", false, true, false, false),
            createPermissionMap("/projects", false, true, false, false),
            createPermissionMap("/todos", false, true, false, false),
            createPermissionMap("/todos/create", false, true, false, false)
        );
        defaultPermissions.put("USER", userPermissions);
        
        // 프리미엄 사용자 권한 (일반 사용자 권한 + dating에서 CRU만 가능)
        List<Map<String, Object>> premiumPermissions = new ArrayList<>(userPermissions);
        premiumPermissions.addAll(Arrays.asList(
            createPermissionMap("/history", false, true, false, false),
            createPermissionMap("/dating", true, true, true, false), // CRU만 가능, D는 불가
            createPermissionMap("/expense", false, true, false, false)
        ));
        defaultPermissions.put("PREMIUM", premiumPermissions);
        
        // 관리자 권한 (모든 메뉴에서 모든 CRUD 가능)
        List<Map<String, Object>> adminPermissions = Arrays.asList(
            createPermissionMap("/", true, true, true, true),
            createPermissionMap("/portfolio", true, true, true, true),
            createPermissionMap("/projects", true, true, true, true),
            createPermissionMap("/history", true, true, true, true),
            createPermissionMap("/dating", true, true, true, true),
            createPermissionMap("/todos", true, true, true, true),
            createPermissionMap("/todos/create", true, true, true, true),
            createPermissionMap("/expense", true, true, true, true),
            createPermissionMap("/admin", true, true, true, true),
            createPermissionMap("/admin/users", true, true, true, true),
            createPermissionMap("/admin/menu-management", true, true, true, true)
        );
        defaultPermissions.put("ADMIN", adminPermissions);
        
        saveCrudPermissions(defaultPermissions);
    }
    
    /**
     * 특정 권한의 CRUD 권한 업데이트
     */
    @Transactional
    public void updateRoleCrudPermissions(Role role, List<MenuCrudPermission> permissions) {
        try {
            // 해당 권한의 기존 권한 삭제
            menuCrudPermissionRepository.deleteByRole(role);
            
            // 새로운 권한 저장
            if (!permissions.isEmpty()) {
                menuCrudPermissionRepository.saveAll(permissions);
            }
        } catch (Exception e) {
            System.out.println("Error updating role CRUD permissions: " + e.getMessage());
            throw e;
        }
    }
    
    /**
     * 권한 맵 생성 헬퍼 메서드
     */
    private Map<String, Object> createPermissionMap(String menuPath, Boolean canCreate, Boolean canRead, Boolean canUpdate, Boolean canDelete) {
        Map<String, Object> permission = new HashMap<>();
        permission.put("menuPath", menuPath);
        permission.put("canCreate", canCreate);
        permission.put("canRead", canRead);
        permission.put("canUpdate", canUpdate);
        permission.put("canDelete", canDelete);
        return permission;
    }
}
