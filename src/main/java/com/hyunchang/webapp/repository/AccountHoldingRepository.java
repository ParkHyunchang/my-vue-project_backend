package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.AccountHolding;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountHoldingRepository extends JpaRepository<AccountHolding, Long> {
    List<AccountHolding> findByAccountIdOrderByIdAsc(Long accountId);

    Optional<AccountHolding> findByIdAndAccountId(Long id, Long accountId);

    boolean existsByAccountIdAndMarketAndSymbol(Long accountId, String market, String symbol);

    long countByAccountUserUserId(String userId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(
            "delete from AccountHolding h where h.account.id in (select a.id from PortfolioAccount a where a.user.userId = :userId)")
    int deleteManualByUserId(
            @org.springframework.data.repository.query.Param("userId") String userId);
}
