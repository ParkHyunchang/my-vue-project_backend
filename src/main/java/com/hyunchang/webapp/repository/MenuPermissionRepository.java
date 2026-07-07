package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.MenuPermission;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MenuPermissionRepository extends JpaRepository<MenuPermission, Long> {

    List<MenuPermission> findByRoleName(String roleName);

    List<MenuPermission> findByRoleNameIn(List<String> roleNames);

    void deleteByRoleName(String roleName);

    void deleteByMenuPath(String menuPath);

    boolean existsByRoleNameAndMenuPath(String roleName, String menuPath);
}
