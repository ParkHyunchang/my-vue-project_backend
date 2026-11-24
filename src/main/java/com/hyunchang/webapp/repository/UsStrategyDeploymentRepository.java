package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.UsStrategyDeployment;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;

public interface UsStrategyDeploymentRepository extends JpaRepository<UsStrategyDeployment, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from UsStrategyDeployment d where d.id = 1")
    Optional<UsStrategyDeployment> findForUpdate();
}
