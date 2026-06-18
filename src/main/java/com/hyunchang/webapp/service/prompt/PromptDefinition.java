package com.hyunchang.webapp.service.prompt;

import java.util.List;

/**
 * 편집 가능한 AI 프롬프트 한 종류의 정의.
 *
 * 프롬프트는 세 부분으로 구성된다:
 *   1. instruction (편집 가능) — 페르소나 + "어떻게 분석/답변할지" 지침. 관리자가 수정하는 유일한 부분.
 *   2. fixedContext (고정)    — 분석에 들어가는 데이터 영역. {{변수}} 를 포함하며 코드가 값을 채운다.
 *   3. fixedSchema  (고정)    — 응답 형식(JSON 스키마). 사람이 건드리면 파싱이 깨지므로 고정.
 *
 * 최종 프롬프트 = instruction + fixedContext(값 치환) + fixedSchema.
 */
public class PromptDefinition {

    private final String key;
    private final String displayName;
    private final String category;
    private final String description;
    private final List<PromptVariable> variables;   // fixedContext 에 자동으로 들어가는 데이터(읽기 전용 안내용)
    private final String defaultInstruction;        // 편집 가능 — 기본 지침
    private final String fixedContext;               // 고정 — 데이터 영역({{변수}})
    private final String fixedSchema;                // 고정 — 응답 스키마

    public PromptDefinition(String key, String displayName, String category, String description,
                            List<PromptVariable> variables, String defaultInstruction,
                            String fixedContext, String fixedSchema) {
        this.key = key;
        this.displayName = displayName;
        this.category = category;
        this.description = description;
        this.variables = variables;
        this.defaultInstruction = defaultInstruction;
        this.fixedContext = fixedContext;
        this.fixedSchema = fixedSchema;
    }

    public String getKey() { return key; }
    public String getDisplayName() { return displayName; }
    public String getCategory() { return category; }
    public String getDescription() { return description; }
    public List<PromptVariable> getVariables() { return variables; }
    public String getDefaultInstruction() { return defaultInstruction; }
    public String getFixedContext() { return fixedContext; }
    public String getFixedSchema() { return fixedSchema; }

    /** 관리 화면에서 '자동으로 붙는 고정 영역'을 한 덩어리로 미리보기 위한 문자열. */
    public String fixedPreview() {
        return (fixedContext == null ? "" : fixedContext.strip())
            + "\n\n"
            + (fixedSchema == null ? "" : fixedSchema.strip());
    }
}
