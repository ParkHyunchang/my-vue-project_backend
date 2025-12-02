package com.hyunchang.webapp.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class FindIdResponse {
    private final String maskedUserId;
    private final String message;
}

