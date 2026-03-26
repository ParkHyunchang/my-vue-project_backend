package com.hyunchang.webapp.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ChatRequest {
    private String sessionKey;
    private String content;
    private String username;
    private String anonId;
}
