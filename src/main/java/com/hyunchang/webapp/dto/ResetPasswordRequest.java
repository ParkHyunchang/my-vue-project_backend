package com.hyunchang.webapp.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ResetPasswordRequest {
    private String userId;
    private String email;
    private String phone;
    private String newPassword;
}

