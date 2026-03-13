package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.StockHolding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StockHoldingRepository extends JpaRepository<StockHolding, Long> {

    List<StockHolding> findByUserUserIdOrderByIdAsc(String userId);

    Optional<StockHolding> findByIdAndUserUserId(Long id, String userId);
}
