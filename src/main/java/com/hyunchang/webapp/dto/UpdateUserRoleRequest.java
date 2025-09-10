package com.hyunchang.webapp.dto;

import com.hyunchang.webapp.entity.Role;
import lombok.Data;

@Data
public class UpdateUserRoleRequest {
    private Role role;
}
