package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.IrpHolding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface IrpHoldingRepository extends JpaRepository<IrpHolding, Long> {

    List<IrpHolding> findByUserUserIdOrderByIdAsc(String userId);

    Optional<IrpHolding> findByIdAndUserUserId(Long id, String userId);
}
