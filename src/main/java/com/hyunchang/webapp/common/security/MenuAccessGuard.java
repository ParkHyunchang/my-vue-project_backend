package com.hyunchang.webapp.common.security;

import com.hyunchang.webapp.common.web.ApiResponses;
import com.hyunchang.webapp.service.MenuPermissionService;
import com.hyunchang.webapp.util.SecurityUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Component
public class MenuAccessGuard {
    private final MenuPermissionService menuPermissionService;

    public MenuAccessGuard(MenuPermissionService menuPermissionService) {
        this.menuPermissionService = menuPermissionService;
    }

    public boolean hasAccess(String menuPath) {
        return menuPermissionService.hasMenuAccess(SecurityUtils.getCurrentUserRoleName(), menuPath);
    }

    public ResponseEntity<String> forbidden() {
        return ApiResponses.forbidden();
    }
}
