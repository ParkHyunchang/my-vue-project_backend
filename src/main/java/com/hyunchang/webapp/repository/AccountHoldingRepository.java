package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.AccountHolding;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountHoldingRepository extends JpaRepository<AccountHolding, Long> {
    List<AccountHolding> findByAccountIdOrderByIdAsc(Long accountId);

    Optional<AccountHolding> findByIdAndAccountId(Long id, Long accountId);

    boolean existsByAccountIdAndMarketAndSymbol(Long accountId, String market, String symbol);
}
