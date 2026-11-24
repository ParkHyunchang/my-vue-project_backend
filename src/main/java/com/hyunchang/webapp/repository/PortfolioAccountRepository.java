package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.PortfolioAccount;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PortfolioAccountRepository extends JpaRepository<PortfolioAccount, Long> {
    List<PortfolioAccount> findByUserUserIdOrderByIdAsc(String userId);

    Optional<PortfolioAccount> findByAccountKeyAndUserUserId(String key, String userId);
}
