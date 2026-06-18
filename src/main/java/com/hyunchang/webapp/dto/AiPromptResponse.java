package com.hyunchang.webapp.dto;

import com.hyunchang.webapp.service.prompt.AiPromptService.PromptAdminView;
import com.hyunchang.webapp.service.prompt.PromptDefinition;
import com.hyunchang.webapp.service.prompt.PromptVariable;

import java.util.List;

/** 관리 화면용 프롬프트 1건. 편집 대상은 '지침(instruction)'뿐이고, 데이터/응답형식은 읽기 전용으로 함께 내려준다. */
public class AiPromptResponse {

    private String key;
    private String displayName;
    private String category;
    private String description;
    private List<PromptVariable> variables;          // 고정 데이터 영역에 자동으로 들어가는 값(읽기 전용 안내)

    private String defaultInstruction;               // 코드 기본 지침
    private String currentInstruction;               // 저장된 지침 오버라이드 (없으면 null)
    private String effectiveInstruction;             // 실제 사용 중인 지침 (편집기 초기값)
    private boolean customized;                       // true=커스텀 지침, false=기본 지침

    private String fixedContext;                     // 읽기 전용: 데이터 영역({{변수}})
    private String fixedSchema;                      // 읽기 전용: 응답 스키마
    private String fixedPreview;                     // 읽기 전용: 데이터+스키마 한 덩어리 미리보기

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
        r.defaultInstruction = def.getDefaultInstruction();
        r.currentInstruction = view.currentContent();
        r.effectiveInstruction = view.customized() ? view.currentContent() : def.getDefaultInstruction();
        r.customized = view.customized();
        r.fixedContext = def.getFixedContext();
        r.fixedSchema = def.getFixedSchema();
        r.fixedPreview = def.fixedPreview();
        r.updatedAt = view.updatedAt();
        r.updatedBy = view.updatedBy();
        return r;
    }

    public String getKey() { return key; }
    public String getDisplayName() { return displayName; }
    public String getCategory() { return category; }
    public String getDescription() { return description; }
    public List<PromptVariable> getVariables() { return variables; }
    public String getDefaultInstruction() { return defaultInstruction; }
    public String getCurrentInstruction() { return currentInstruction; }
    public String getEffectiveInstruction() { return effectiveInstruction; }
    public boolean isCustomized() { return customized; }
    public String getFixedContext() { return fixedContext; }
    public String getFixedSchema() { return fixedSchema; }
    public String getFixedPreview() { return fixedPreview; }
    public String getUpdatedAt() { return updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
}
