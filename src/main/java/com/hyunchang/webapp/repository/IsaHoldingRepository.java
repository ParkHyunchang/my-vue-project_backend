package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.IsaHolding;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IsaHoldingRepository extends JpaRepository<IsaHolding, Long> {

    List<IsaHolding> findByUserUserIdOrderByIdAsc(String userId);

    Optional<IsaHolding> findByIdAndUserUserId(Long id, String userId);
}
