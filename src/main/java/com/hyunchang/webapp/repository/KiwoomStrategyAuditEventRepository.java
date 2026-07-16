package com.hyunchang.webapp.repository;
import com.hyunchang.webapp.entity.KiwoomStrategyAuditEvent; import java.util.List; import org.springframework.data.jpa.repository.JpaRepository;
public interface KiwoomStrategyAuditEventRepository extends JpaRepository<KiwoomStrategyAuditEvent,Long>{ List<KiwoomStrategyAuditEvent> findTop20ByOrderByIdDesc(); }
