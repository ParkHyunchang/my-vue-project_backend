package com.hyunchang.webapp.service.prompt;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 편집 가능한 AI 프롬프트 한 종류의 정의 (코드에 고정된 기본값 + 메타데이터).
 *  - key          : 식별자 (DB 오버라이드 매칭 키)
 *  - displayName  : 관리 화면에 보여줄 이름
 *  - category     : 그룹핑용 (주식 / 여행 / 부동산 / 포트폴리오 / 일기)
 *  - description  : 이 프롬프트가 어디에 쓰이는지 설명
 *  - variables    : {{이름}} 으로 치환 가능한 변수 목록
 *  - defaultTemplate : 코드가 가진 기본 프롬프트 (되돌리기·폴백의 기준)
 */
public class PromptDefinition {

    private final String key;
    private final String displayName;
    private final String category;
    private final String description;
    private final List<PromptVariable> variables;
    private final String defaultTemplate;

    public PromptDefinition(String key, String displayName, String category, String description,
                            List<PromptVariable> variables, String defaultTemplate) {
        this.key = key;
        this.displayName = displayName;
        this.category = category;
        this.description = description;
        this.variables = variables;
        this.defaultTemplate = defaultTemplate;
    }

    public String getKey() { return key; }
    public String getDisplayName() { return displayName; }
    public String getCategory() { return category; }
    public String getDescription() { return description; }
    public List<PromptVariable> getVariables() { return variables; }
    public String getDefaultTemplate() { return defaultTemplate; }

    /** 이 프롬프트에서 허용되는 변수 이름 집합. */
    public Set<String> variableNames() {
        return variables.stream().map(PromptVariable::name).collect(Collectors.toSet());
    }
}
