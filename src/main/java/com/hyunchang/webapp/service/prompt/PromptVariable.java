package com.hyunchang.webapp.service.prompt;

/**
 * 프롬프트 안에서 {{이름}} 형태로 치환되는 변수 한 개의 메타데이터.
 * name 은 {{...}} 안에 들어가는 토큰, description 은 관리자 편집 화면에 보여줄 설명.
 */
public record PromptVariable(String name, String description) {
}
