package com.hyunchang.webapp.service;

import com.hyunchang.webapp.entity.KiwoomUsAuditEvent;
import com.hyunchang.webapp.repository.KiwoomUsAuditEventRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class KiwoomUsAuditService {
    private static final Logger log = LoggerFactory.getLogger(KiwoomUsAuditService.class);
    private final KiwoomUsAuditEventRepository repository;

    public KiwoomUsAuditService(KiwoomUsAuditEventRepository repository) {
        this.repository = repository;
    }

    public KiwoomUsAuditEvent log(String type, Long proposalId, String message) {
        log.info("[미국자동매매][{}][proposal={}] {}", type, proposalId, message);
        return repository.save(new KiwoomUsAuditEvent(type, proposalId, message));
    }

    public List<KiwoomUsAuditEvent> recent() {
        return repository.findTop100ByOrderByIdDesc();
    }
}
