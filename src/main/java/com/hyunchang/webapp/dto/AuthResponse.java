package com.hyunchang.webapp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {
    // token 필드는 더 이상 응답 본문에 포함하지 않음 (httpOnly 쿠키 사용)
    private String username;
    private String name;
    private String email;
    private String phone;
    private String role;
    private String message;
}
