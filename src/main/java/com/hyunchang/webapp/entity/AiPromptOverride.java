package com.hyunchang.webapp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * AI 프롬프트 커스텀 오버라이드.
 *
 * 각 프롬프트(STOCK_ANALYSIS, TRAVEL_CREATE ...)의 기본 템플릿은 코드(AiPromptCatalog)에 있고,
 * 관리자가 화면에서 수정하면 이 테이블에 '오버라이드'로만 저장된다.
 * - 행이 없거나 content 가 비어 있으면 → 코드 기본 템플릿으로 동작 (기본값 사용 중)
 * - content 가 있으면 → 그 내용으로 동작 (커스텀 적용 중)
 * '기본값으로 되돌리기' 는 이 행을 삭제하는 것과 같다.
 */
@Entity
@Table(name = "ai_prompt_override")
public class AiPromptOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "prompt_key", nullable = false, unique = true, length = 64)
    private String promptKey;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void touch() {
        updatedAt = LocalDateTime.now();
    }

    public AiPromptOverride() {}

    public AiPromptOverride(String promptKey, String content, String updatedBy) {
        this.promptKey = promptKey;
        this.content = content;
        this.updatedBy = updatedBy;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getPromptKey() { return promptKey; }
    public void setPromptKey(String promptKey) { this.promptKey = promptKey; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
