package com.hyunchang.webapp.dto;

import com.hyunchang.webapp.entity.Role;
import lombok.Data;

@Data
public class CreateUserRequest {
    private String username;
    private String email;
    private String password;
    private Role role;
}
