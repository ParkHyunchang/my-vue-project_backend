package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.PropertyHolding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PropertyHoldingRepository extends JpaRepository<PropertyHolding, Long> {

    List<PropertyHolding> findByUserUserIdOrderByIdAsc(String userId);

    Optional<PropertyHolding> findByIdAndUserUserId(Long id, String userId);
}
