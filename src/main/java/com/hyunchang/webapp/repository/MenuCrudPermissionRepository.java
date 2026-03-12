package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.MenuCrudPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MenuCrudPermissionRepository extends JpaRepository<MenuCrudPermission, Long> {

    Optional<MenuCrudPermission> findByRoleNameAndMenuPath(String roleName, String menuPath);

    List<MenuCrudPermission> findByRoleName(String roleName);

    List<MenuCrudPermission> findByMenuPath(String menuPath);

    void deleteByRoleName(String roleName);

    void deleteByMenuPath(String menuPath);
}
