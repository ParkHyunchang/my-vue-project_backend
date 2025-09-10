package com.hyunchang.webapp.dto;

import com.hyunchang.webapp.entity.Role;
import lombok.Data;

@Data
public class RegisterRequest {
    private String username;
    private String email;
    private String password;
    private Role role = Role.USER; // 기본값은 USER
}
