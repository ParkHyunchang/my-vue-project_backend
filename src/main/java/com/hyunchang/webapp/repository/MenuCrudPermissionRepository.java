package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.MenuCrudPermission;
import com.hyunchang.webapp.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MenuCrudPermissionRepository extends JpaRepository<MenuCrudPermission, Long> {
    
    /**
     * 특정 권한과 메뉴 경로로 CRUD 권한 조회
     */
    Optional<MenuCrudPermission> findByRoleAndMenuPath(Role role, String menuPath);
    
    /**
     * 특정 권한의 모든 메뉴 CRUD 권한 조회
     */
    List<MenuCrudPermission> findByRole(Role role);
    
    /**
     * 특정 메뉴 경로의 모든 권한별 CRUD 권한 조회
     */
    List<MenuCrudPermission> findByMenuPath(String menuPath);
    
    /**
     * 특정 권한의 메뉴 경로들 조회
     */
    @Query("SELECT mcp.menuPath FROM MenuCrudPermission mcp WHERE mcp.role = :role")
    List<String> findMenuPathsByRole(@Param("role") Role role);
    
    /**
     * 특정 권한과 메뉴 경로에서 특정 CRUD 권한 확인
     */
    @Query("SELECT CASE WHEN COUNT(mcp) > 0 THEN true ELSE false END FROM MenuCrudPermission mcp " +
           "WHERE mcp.role = :role AND mcp.menuPath = :menuPath AND " +
           "(:operation = 'CREATE' AND mcp.canCreate = true) OR " +
           "(:operation = 'READ' AND mcp.canRead = true) OR " +
           "(:operation = 'UPDATE' AND mcp.canUpdate = true) OR " +
           "(:operation = 'DELETE' AND mcp.canDelete = true)")
    boolean hasPermission(@Param("role") Role role, @Param("menuPath") String menuPath, @Param("operation") String operation);
    
    /**
     * 특정 권한의 특정 메뉴에서 삭제 권한 확인
     */
    @Query("SELECT mcp.canDelete FROM MenuCrudPermission mcp WHERE mcp.role = :role AND mcp.menuPath = :menuPath")
    Optional<Boolean> canDelete(@Param("role") Role role, @Param("menuPath") String menuPath);
    
    /**
     * 특정 권한의 특정 메뉴에서 생성 권한 확인
     */
    @Query("SELECT mcp.canCreate FROM MenuCrudPermission mcp WHERE mcp.role = :role AND mcp.menuPath = :menuPath")
    Optional<Boolean> canCreate(@Param("role") Role role, @Param("menuPath") String menuPath);
    
    /**
     * 특정 권한의 특정 메뉴에서 수정 권한 확인
     */
    @Query("SELECT mcp.canUpdate FROM MenuCrudPermission mcp WHERE mcp.role = :role AND mcp.menuPath = :menuPath")
    Optional<Boolean> canUpdate(@Param("role") Role role, @Param("menuPath") String menuPath);
    
    /**
     * 특정 권한의 특정 메뉴에서 조회 권한 확인
     */
    @Query("SELECT mcp.canRead FROM MenuCrudPermission mcp WHERE mcp.role = :role AND mcp.menuPath = :menuPath")
    Optional<Boolean> canRead(@Param("role") Role role, @Param("menuPath") String menuPath);
    
    /**
     * 특정 권한의 모든 CRUD 권한 삭제
     */
    void deleteByRole(Role role);
}
