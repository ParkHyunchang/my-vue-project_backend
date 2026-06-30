package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.GeneralHolding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GeneralHoldingRepository extends JpaRepository<GeneralHolding, Long> {

    List<GeneralHolding> findByUserUserIdOrderByIdAsc(String userId);

    Optional<GeneralHolding> findByIdAndUserUserId(Long id, String userId);
}
