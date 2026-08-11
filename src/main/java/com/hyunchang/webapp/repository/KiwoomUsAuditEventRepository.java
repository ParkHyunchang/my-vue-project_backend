package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.KiwoomUsAuditEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KiwoomUsAuditEventRepository extends JpaRepository<KiwoomUsAuditEvent, Long> {
    List<KiwoomUsAuditEvent> findTop100ByOrderByIdDesc();
}
