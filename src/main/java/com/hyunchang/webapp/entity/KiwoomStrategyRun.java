package com.hyunchang.webapp.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "kiwoom_strategy_run")
public class KiwoomStrategyRun {
    public enum Status {
        SUCCESS,
        FAILED,
        PARSE_FAILED,
        BLOCKED,
        SKIPPED
    }

    public enum TriggeredBy {
        MANUAL,
        SCHEDULE,
        RISK
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // columnDefinition을 varchar로 명시 — MySQLDialect가 기본으로 native ENUM 컬럼을 생성하는데,
    // ddl-auto=update는 기존 컬럼의 enum 허용값을 넓혀주지 않아 새 상수 추가 시마다
    // "Data truncated for column" 오류가 재발했다 (SKIPPED, RISK 추가 때 실제 발생).
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(20)")
    private TriggeredBy triggeredBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(20)")
    private Status status;

    private String providerName;
    private String model;

    @Column(columnDefinition = "TEXT")
    private String marketView;

    @Column(length = 500)
    private String errorMessage;

    private int promptChars;

    /** Token counts are estimates when the provider does not return usage metadata. */
    private int inputTokens;

    private int outputTokens;
    private boolean aiCalled;
    private LocalDateTime createdAt;

    @PrePersist
    void created() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public TriggeredBy getTriggeredBy() {
        return triggeredBy;
    }

    public void setTriggeredBy(TriggeredBy v) {
        triggeredBy = v;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status v) {
        status = v;
    }

    public String getProviderName() {
        return providerName;
    }

    public void setProviderName(String v) {
        providerName = v;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String v) {
        model = v;
    }

    public String getMarketView() {
        return marketView;
    }

    public void setMarketView(String v) {
        marketView = v;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String v) {
        errorMessage = v;
    }

    public int getPromptChars() {
        return promptChars;
    }

    public void setPromptChars(int v) {
        promptChars = v;
    }

    public int getInputTokens() {
        return inputTokens;
    }

    public void setInputTokens(int v) {
        inputTokens = v;
    }

    public int getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(int v) {
        outputTokens = v;
    }

    public boolean isAiCalled() {
        return aiCalled;
    }

    public void setAiCalled(boolean v) {
        aiCalled = v;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
