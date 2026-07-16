package com.hyunchang.webapp.repository;
import com.hyunchang.webapp.entity.KiwoomStrategyRun; import org.springframework.data.domain.*; import org.springframework.data.jpa.repository.JpaRepository;
public interface KiwoomStrategyRunRepository extends JpaRepository<KiwoomStrategyRun,Long>{ Page<KiwoomStrategyRun> findByOrderByIdDesc(Pageable pageable); }
