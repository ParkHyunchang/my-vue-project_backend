package com.hyunchang.webapp.dto;

import com.hyunchang.webapp.entity.Role;
import lombok.Data;

@Data
public class RegisterRequest {
    private String userId;    // 로그인용 아이디
    private String name;      // 사용자 이름
    private String email;
    private String phone;
    private String password;
    private Role role = Role.USER; // 기본값은 USER
}
