package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.KiwoomUsStrategyRun;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KiwoomUsStrategyRunRepository extends JpaRepository<KiwoomUsStrategyRun, Long> {
    List<KiwoomUsStrategyRun> findTop30ByOrderByIdDesc();
}
