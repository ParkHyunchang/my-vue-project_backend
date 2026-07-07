package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.RoleInfo;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RoleInfoRepository extends JpaRepository<RoleInfo, Long> {
    Optional<RoleInfo> findByRoleName(String roleName);

    boolean existsByRoleName(String roleName);
}
