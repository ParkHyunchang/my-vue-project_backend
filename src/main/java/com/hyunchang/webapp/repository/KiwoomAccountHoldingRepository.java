package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.KiwoomAccountHolding;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface KiwoomAccountHoldingRepository extends JpaRepository<KiwoomAccountHolding, Long> {
    Optional<KiwoomAccountHolding> findByStockCode(String stockCode);

    List<KiwoomAccountHolding> findByActiveTrueOrderByStockCodeAsc();
}
