package com.hyunchang.webapp.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** One account-bound deployment slot. Risk and holdings are deliberately not stored here. */
@Entity
@Table(name = "us_strategy_deployment")
@Getter
@Setter
public class UsStrategyDeployment {
    @Id private Long id = 1L;
    @Version private Long revision;

    @Column(length = 64)
    private String accountHash;

    private boolean managed;
    private boolean active;

    @Column(length = 64)
    private String deploymentId;

    @Column(columnDefinition = "LONGTEXT")
    private String pendingEnvelope;

    @Column(columnDefinition = "LONGTEXT")
    private String activePayload;

    @Column(columnDefinition = "LONGTEXT")
    private String executionState;

    @Column(length = 10)
    private String claimedSignalDate;

    @Column(length = 64)
    private String approvedAt;

    @Column(length = 1000)
    private String lastExecution;
}
