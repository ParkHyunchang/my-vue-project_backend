package com.hyunchang.webapp.dto;

import com.hyunchang.webapp.entity.RoleInfo;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoleInfoResponse {
    private Long id;
    private String roleName;
    private String displayName;
    private String description;
    private boolean isDefault;
    private long userCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static RoleInfoResponse from(RoleInfo roleInfo, long userCount) {
        return RoleInfoResponse.builder()
                .id(roleInfo.getId())
                .roleName(roleInfo.getRoleName())
                .displayName(roleInfo.getDisplayName())
                .description(roleInfo.getDescription())
                .isDefault(roleInfo.isDefault())
                .userCount(userCount)
                .createdAt(roleInfo.getCreatedAt())
                .updatedAt(roleInfo.getUpdatedAt())
                .build();
    }
}
