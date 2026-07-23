package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.KiwoomStrategyRun;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KiwoomStrategyRunRepository extends JpaRepository<KiwoomStrategyRun, Long> {
    Page<KiwoomStrategyRun> findByOrderByIdDesc(Pageable pageable);

    List<KiwoomStrategyRun> findByCreatedAtBefore(LocalDateTime cutoff);
}
