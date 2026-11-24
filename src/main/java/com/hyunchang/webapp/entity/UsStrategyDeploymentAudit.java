package com.hyunchang.webapp.entity;

import jakarta.persistence.*;
import lombok.Getter;

@Entity
@Table(name = "us_strategy_deployment_audit")
@Getter
public class UsStrategyDeploymentAudit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64)
    private String occurredAt;

    @Column(length = 150)
    private String actor;

    @Column(length = 64)
    private String accountHash;

    @Column(length = 64)
    private String deploymentId;

    @Column(length = 32)
    private String event;

    @Column(length = 1000)
    private String detail;

    protected UsStrategyDeploymentAudit() {}

    public UsStrategyDeploymentAudit(
            String at,
            String actor,
            String account,
            String deployment,
            String event,
            String detail) {
        this.occurredAt = at;
        this.actor = actor;
        this.accountHash = account;
        this.deploymentId = deployment;
        this.event = event;
        this.detail = detail;
    }
}
