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
@Table(name = "kiwoom_us_strategy_runs")
public class KiwoomUsStrategyRun {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 30)
    private String status;

    @Column(length = 30)
    private String triggeredBy;

    @Column(length = 2000)
    private String candidateSummary;

    @Column(length = 1000)
    private String message;

    private LocalDateTime createdAt;

    @PrePersist
    void created() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String v) {
        status = v;
    }

    public String getTriggeredBy() {
        return triggeredBy;
    }

    public void setTriggeredBy(String v) {
        triggeredBy = v;
    }

    public String getCandidateSummary() {
        return candidateSummary;
    }

    public void setCandidateSummary(String v) {
        candidateSummary = v;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String v) {
        message = v;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
