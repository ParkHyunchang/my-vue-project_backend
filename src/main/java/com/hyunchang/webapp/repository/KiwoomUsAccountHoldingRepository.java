package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.KiwoomUsAccountHolding;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KiwoomUsAccountHoldingRepository
        extends JpaRepository<KiwoomUsAccountHolding, Long> {
    Optional<KiwoomUsAccountHolding> findByExchangeAndSymbol(String exchange, String symbol);

    List<KiwoomUsAccountHolding> findByActiveTrueOrderByIdAsc();
}
