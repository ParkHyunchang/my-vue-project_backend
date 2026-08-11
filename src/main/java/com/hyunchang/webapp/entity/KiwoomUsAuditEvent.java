package com.hyunchang.webapp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "kiwoom_us_audit_events")
public class KiwoomUsAuditEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String eventType;

    private Long proposalId;

    @Column(nullable = false, length = 1000)
    private String message;

    private LocalDateTime createdAt;

    public KiwoomUsAuditEvent() {}

    public KiwoomUsAuditEvent(String type, Long proposal, String value) {
        eventType = type;
        proposalId = proposal;
        message = value;
    }

    @PrePersist
    void created() {
        createdAt = LocalDateTime.now();
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
