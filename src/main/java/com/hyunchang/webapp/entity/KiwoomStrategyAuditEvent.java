package com.hyunchang.webapp.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "kiwoom_strategy_audit_event")
public class KiwoomStrategyAuditEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String eventType;

    private Long proposalId;

    @Column(nullable = false, length = 500)
    private String message;

    private LocalDateTime createdAt;

    @PrePersist
    void created() {
        createdAt = LocalDateTime.now();
    }

    public KiwoomStrategyAuditEvent() {}

    public KiwoomStrategyAuditEvent(String type, Long proposalId, String message) {
        this.eventType = type;
        this.proposalId = proposalId;
        this.message = message;
    }

    public Long getId() {
        return id;
    }

    public String getEventType() {
        return eventType;
    }

    public Long getProposalId() {
        return proposalId;
    }

    public String getMessage() {
        return message;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
