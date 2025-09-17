package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.MenuPermission;
import com.hyunchang.webapp.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MenuPermissionRepository extends JpaRepository<MenuPermission, Long> {
    
    List<MenuPermission> findByRole(Role role);
    
    List<MenuPermission> findByRoleIn(List<Role> roles);
    
    @Modifying
    @Query("DELETE FROM MenuPermission mp WHERE mp.role = :role")
    void deleteByRole(@Param("role") Role role);
    
    boolean existsByRoleAndMenuPath(Role role, String menuPath);
}
