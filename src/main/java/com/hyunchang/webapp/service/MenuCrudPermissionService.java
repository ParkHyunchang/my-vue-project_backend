package com.hyunchang.webapp.service;

import com.hyunchang.webapp.entity.MenuCrudPermission;
import com.hyunchang.webapp.repository.MenuCrudPermissionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class MenuCrudPermissionService {

    @Autowired
    private MenuCrudPermissionRepository menuCrudPermissionRepository;

    @PersistenceContext
    private EntityManager entityManager;

    // ===== 조회 =====

    public List<MenuCrudPermission> getPermissionsByRoleName(String roleName) {
        return menuCrudPermissionRepository.findByRoleName(roleName);
    }

    public Map<String, List<MenuCrudPermission>> getAllCrudPermissions() {
        List<MenuCrudPermission> all = menuCrudPermissionRepository.findAll();
        return all.stream().collect(Collectors.groupingBy(MenuCrudPermission::getRoleName));
    }

    // ===== 저장 (권한별 전체 교체) =====

    @Transactional
    public void saveRoleCrudPermissions(String roleName, Map<String, Map<String, Boolean>> menuMap) {
        menuCrudPermissionRepository.deleteByRoleName(roleName);
        entityManager.flush();
        entityManager.clear();

        List<MenuCrudPermission> toSave = new ArrayList<>();
        menuMap.forEach((menuPath, perms) -> toSave.add(new MenuCrudPermission(
            roleName, menuPath,
            perms.getOrDefault("canCreate", false),
            perms.getOrDefault("canRead", false),
            perms.getOrDefault("canUpdate", false),
            perms.getOrDefault("canDelete", false)
        )));
        menuCrudPermissionRepository.saveAll(toSave);
    }

    // ===== 초기화 =====

    @Transactional
    public void initializeDefaultCrudPermissions() {
        Map<String, List<Map<String, Object>>> defaults = new HashMap<>();

        defaults.put("USER", Arrays.asList(
            perm("/", false, true, false, false),
            perm("/portfolio", false, true, false, false),
            perm("/projects", false, true, false, false),
            perm("/todos", false, true, false, false),
            perm("/todos/create", false, true, false, false)
        ));

        List<Map<String, Object>> premium = new ArrayList<>(defaults.get("USER"));
        premium.addAll(Arrays.asList(
            perm("/history", false, true, false, false),
            perm("/dating", true, true, true, false),
            perm("/dating_sys", true, true, true, false),
            perm("/expense", false, true, false, false)
        ));
        defaults.put("PREMIUM", premium);

        defaults.put("ADMIN", Arrays.asList(
            perm("/", true, true, true, true),
            perm("/portfolio", true, true, true, true),
            perm("/projects", true, true, true, true),
            perm("/history", true, true, true, true),
            perm("/dating", true, true, true, true),
            perm("/dating_sys", true, true, true, true),
            perm("/todos", true, true, true, true),
            perm("/todos/create", true, true, true, true),
            perm("/expense", true, true, true, true),
            perm("/admin", true, true, true, true),
            perm("/admin/users", true, true, true, true),
            perm("/admin/menu-management", true, true, true, true),
            perm("/admin/role-management", true, true, true, true)
        ));

        defaults.forEach((roleName, menuPerms) -> {
            List<String> existing = menuCrudPermissionRepository.findByRoleName(roleName)
                .stream().map(MenuCrudPermission::getMenuPath).collect(Collectors.toList());

            List<MenuCrudPermission> missing = menuPerms.stream()
                .filter(p -> !existing.contains((String) p.get("menuPath")))
                .map(p -> new MenuCrudPermission(
                    roleName,
                    (String) p.get("menuPath"),
                    (Boolean) p.get("canCreate"),
                    (Boolean) p.get("canRead"),
                    (Boolean) p.get("canUpdate"),
                    (Boolean) p.get("canDelete")
                ))
                .collect(Collectors.toList());

            if (!missing.isEmpty()) {
                menuCrudPermissionRepository.saveAll(missing);
            }
        });
    }

    private Map<String, Object> perm(String path, boolean c, boolean r, boolean u, boolean d) {
        Map<String, Object> m = new HashMap<>();
        m.put("menuPath", path);
        m.put("canCreate", c);
        m.put("canRead", r);
        m.put("canUpdate", u);
        m.put("canDelete", d);
        return m;
    }
}
