package com.hyunchang.webapp.util;

import com.hyunchang.webapp.entity.Role;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 보안 관련 유틸리티 클래스
 */
public class SecurityUtils {
    
    /**
     * 현재 인증된 사용자의 권한을 가져옵니다.
     * 
     * @return 현재 사용자의 Role, 인증되지 않은 경우 USER 반환
     */
    public static Role getCurrentUserRole() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getAuthorities() != null) {
            String authority = authentication.getAuthorities().iterator().next().getAuthority();
            if (authority.startsWith("ROLE_")) {
                String roleName = authority.substring(5); // "ROLE_" 제거
                try {
                    return Role.valueOf(roleName);
                } catch (IllegalArgumentException e) {
                    System.out.println("Invalid role: " + roleName);
                }
            }
        }
        return Role.USER; // 기본값
    }
}
