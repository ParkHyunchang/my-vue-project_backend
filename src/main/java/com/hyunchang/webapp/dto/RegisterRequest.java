package com.hyunchang.webapp.dto;

import lombok.Data;

@Data
public class RegisterRequest {
    private String userId;
    private String name;
    private String email;
    private String phone;
    private String password;
    private String role = "USER"; // 기본값은 USER
}
