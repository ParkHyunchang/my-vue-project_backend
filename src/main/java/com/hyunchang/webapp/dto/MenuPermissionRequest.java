package com.hyunchang.webapp.dto;

import java.util.List;
import java.util.Map;

public class MenuPermissionRequest {
    private Map<String, List<String>> permissions;
    
    // 기본 생성자
    public MenuPermissionRequest() {}
    
    // 생성자
    public MenuPermissionRequest(Map<String, List<String>> permissions) {
        this.permissions = permissions;
    }
    
    // Getters and Setters
    public Map<String, List<String>> getPermissions() {
        return permissions;
    }
    
    public void setPermissions(Map<String, List<String>> permissions) {
        this.permissions = permissions;
    }
}
