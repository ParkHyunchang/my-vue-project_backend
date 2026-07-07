package com.hyunchang.webapp.dto;

/** 프롬프트 오버라이드 저장 요청 본문. */
public class AiPromptUpdateRequest {
    private String content;

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
