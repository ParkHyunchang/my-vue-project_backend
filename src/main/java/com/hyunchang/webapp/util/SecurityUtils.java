package com.hyunchang.webapp.util;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public class SecurityUtils {

    public static String getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()) {
            return authentication.getName();
        }
        return "unknown";
    }

    public static String getCurrentUserRoleName() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getAuthorities() != null
                && !authentication.getAuthorities().isEmpty()) {
            String authority = authentication.getAuthorities().iterator().next().getAuthority();
            if (authority.startsWith("ROLE_")) {
                return authority.substring(5);
            }
        }
        return "USER";
    }
}
