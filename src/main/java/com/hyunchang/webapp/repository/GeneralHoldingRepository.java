package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.GeneralHolding;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GeneralHoldingRepository extends JpaRepository<GeneralHolding, Long> {

    List<GeneralHolding> findByUserUserIdOrderByIdAsc(String userId);

    Optional<GeneralHolding> findByIdAndUserUserId(Long id, String userId);

    long countByUserUserId(String userId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(
            "delete from GeneralHolding h where h.user.id in (select u.id from User u where u.userId = :userId)")
    int deleteManualByUserId(
            @org.springframework.data.repository.query.Param("userId") String userId);
}
