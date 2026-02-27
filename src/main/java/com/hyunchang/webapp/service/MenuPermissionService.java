package com.hyunchang.webapp.service;

import com.hyunchang.webapp.entity.MenuPermission;
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

    // ===== 조회 =====

    public List<String> getMenuPermissionsByRoleName(String roleName) {
        return menuPermissionRepository.findByRoleName(roleName).stream()
            .map(MenuPermission::getMenuPath)
            .collect(Collectors.toList());
    }

    public Map<String, List<String>> getAllMenuPermissions() {
        return menuPermissionRepository.findAll().stream()
            .collect(Collectors.groupingBy(
                MenuPermission::getRoleName,
                Collectors.mapping(MenuPermission::getMenuPath, Collectors.toList())
            ));
    }

    // ===== 저장 =====

    @Transactional
    public void saveMenuPermissions(Map<String, List<String>> permissions) {
        menuPermissionRepository.deleteAll();
        entityManager.flush();
        entityManager.clear();

        List<MenuPermission> toSave = new ArrayList<>();
        permissions.forEach((roleName, menuPaths) ->
            menuPaths.forEach(path -> toSave.add(new MenuPermission(roleName, path)))
        );
        if (!toSave.isEmpty()) {
            menuPermissionRepository.saveAll(toSave);
        }
    }

    @Transactional
    public void updateRoleMenuPermissions(String roleName, List<String> menuPaths) {
        menuPermissionRepository.deleteByRoleName(roleName);
        List<MenuPermission> newPerms = menuPaths.stream()
            .map(path -> new MenuPermission(roleName, path))
            .collect(Collectors.toList());
        if (!newPerms.isEmpty()) {
            menuPermissionRepository.saveAll(newPerms);
        }
    }

    // ===== 기본 권한 초기화 =====

    @Transactional
    public void initializeDefaultPermissions() {
        Map<String, List<String>> defaults = new HashMap<>();

        defaults.put("USER", Arrays.asList(
            "/", "/portfolio", "/projects", "/todos", "/todos/create"
        ));
        defaults.put("PREMIUM", Arrays.asList(
            "/", "/portfolio", "/projects", "/history", "/dating", "/dating_sys",
            "/todos", "/todos/create"
        ));
        defaults.put("ADMIN", Arrays.asList(
            "/", "/portfolio", "/projects", "/history", "/dating", "/dating_sys",
            "/todos", "/todos/create", "/expense",
            "/admin", "/admin/users", "/admin/menu-management", "/admin/role-management"
        ));

        defaults.forEach((roleName, menuPaths) -> {
            List<String> existing = menuPermissionRepository.findByRoleName(roleName).stream()
                .map(MenuPermission::getMenuPath)
                .collect(Collectors.toList());

            List<MenuPermission> missing = menuPaths.stream()
                .filter(p -> !existing.contains(p))
                .map(p -> new MenuPermission(roleName, p))
                .collect(Collectors.toList());

            if (!missing.isEmpty()) {
                menuPermissionRepository.saveAll(missing);
            }
        });
    }
}
