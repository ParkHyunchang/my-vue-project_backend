package com.hyunchang.webapp.dto;

import com.hyunchang.webapp.service.prompt.AiPromptService.PromptAdminView;
import com.hyunchang.webapp.service.prompt.PromptDefinition;
import com.hyunchang.webapp.service.prompt.PromptVariable;

import java.util.List;

/** 관리 화면용 프롬프트 1건 (정의 + 현재 상태)을 평탄하게 내려주는 응답. */
public class AiPromptResponse {

    private String key;
    private String displayName;
    private String category;
    private String description;
    private List<PromptVariable> variables;
    private String defaultTemplate;
    private String currentContent;    // 오버라이드 (없으면 null)
    private String effectiveContent;  // 실제 사용 중인 템플릿 (편집기 초기값)
    private boolean customized;        // true=커스텀 적용 중, false=기본값 사용 중
    private String updatedAt;
    private String updatedBy;

    public static AiPromptResponse from(PromptAdminView view) {
        PromptDefinition def = view.definition();
        AiPromptResponse r = new AiPromptResponse();
        r.key = def.getKey();
        r.displayName = def.getDisplayName();
        r.category = def.getCategory();
        r.description = def.getDescription();
        r.variables = def.getVariables();
        r.defaultTemplate = def.getDefaultTemplate();
        r.currentContent = view.currentContent();
        r.effectiveContent = view.customized() ? view.currentContent() : def.getDefaultTemplate();
        r.customized = view.customized();
        r.updatedAt = view.updatedAt();
        r.updatedBy = view.updatedBy();
        return r;
    }

    public String getKey() { return key; }
    public String getDisplayName() { return displayName; }
    public String getCategory() { return category; }
    public String getDescription() { return description; }
    public List<PromptVariable> getVariables() { return variables; }
    public String getDefaultTemplate() { return defaultTemplate; }
    public String getCurrentContent() { return currentContent; }
    public String getEffectiveContent() { return effectiveContent; }
    public boolean isCustomized() { return customized; }
    public String getUpdatedAt() { return updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
}
