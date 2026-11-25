package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.PortfolioAccount;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PortfolioAccountRepository extends JpaRepository<PortfolioAccount, Long> {
    List<PortfolioAccount> findByUserUserIdOrderByIdAsc(String userId);

    Optional<PortfolioAccount> findByAccountKeyAndUserUserId(String key, String userId);

    long countByUserUserId(String userId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(
            "delete from PortfolioAccount h where h.user.id in (select u.id from User u where u.userId = :userId)")
    int deleteManualByUserId(
            @org.springframework.data.repository.query.Param("userId") String userId);
}
