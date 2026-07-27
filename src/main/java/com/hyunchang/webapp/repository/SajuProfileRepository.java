package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.SajuProfile;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SajuProfileRepository extends JpaRepository<SajuProfile, Long> {

    List<SajuProfile> findByUserUserIdOrderByCreatedAtDesc(String userId);

    Optional<SajuProfile> findByIdAndUserUserId(Long id, String userId);
}
