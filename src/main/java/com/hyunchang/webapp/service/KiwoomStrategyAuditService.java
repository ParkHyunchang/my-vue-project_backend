package com.hyunchang.webapp.service;

import com.hyunchang.webapp.entity.KiwoomStrategyAuditEvent;
import com.hyunchang.webapp.repository.KiwoomStrategyAuditEventRepository;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class KiwoomStrategyAuditService {
    private final KiwoomStrategyAuditEventRepository repository;

    public KiwoomStrategyAuditService(KiwoomStrategyAuditEventRepository repository) {
        this.repository = repository;
    }

    public void log(String type, Long proposalId, String message) {
        repository.save(new KiwoomStrategyAuditEvent(type, proposalId, message));
    }

    public List<KiwoomStrategyAuditEvent> recent() {
        return repository.findTop20ByOrderByIdDesc();
    }
}
