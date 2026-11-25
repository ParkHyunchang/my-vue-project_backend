package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.IrpHolding;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IrpHoldingRepository extends JpaRepository<IrpHolding, Long> {

    List<IrpHolding> findByUserUserIdOrderByIdAsc(String userId);

    Optional<IrpHolding> findByIdAndUserUserId(Long id, String userId);

    long countByUserUserId(String userId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(
            "delete from IrpHolding h where h.user.id in (select u.id from User u where u.userId = :userId)")
    int deleteManualByUserId(
            @org.springframework.data.repository.query.Param("userId") String userId);
}
