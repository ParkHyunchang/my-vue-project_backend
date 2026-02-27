package com.hyunchang.webapp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RoleInfoRequest {
    private String roleName;
    private String displayName;
    private String description;
    private Boolean isDefault;
}
