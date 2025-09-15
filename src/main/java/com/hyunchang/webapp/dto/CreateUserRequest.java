package com.hyunchang.webapp.dto;

import com.hyunchang.webapp.entity.Role;
import lombok.Data;

@Data
public class CreateUserRequest {
    private String userId;
    private String name;
    private String email;
    private String phone;
    private String password;
    private Role role;
}
