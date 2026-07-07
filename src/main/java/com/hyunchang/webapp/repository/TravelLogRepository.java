package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.TravelLog;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TravelLogRepository extends JpaRepository<TravelLog, Long> {

    List<TravelLog> findByUserUserIdOrderByStartDateDescIdDesc(String userId);

    Optional<TravelLog> findByIdAndUserUserId(Long id, String userId);
}
