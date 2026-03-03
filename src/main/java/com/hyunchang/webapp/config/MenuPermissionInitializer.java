package com.hyunchang.webapp.config;

import com.hyunchang.webapp.service.MenuDefinitionService;
import com.hyunchang.webapp.service.MenuPermissionService;
import com.hyunchang.webapp.service.MenuCrudPermissionService;
import com.hyunchang.webapp.service.RoleInfoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class MenuPermissionInitializer implements ApplicationRunner {
    
    @Autowired
    private MenuPermissionService menuPermissionService;
    
    @Autowired
    private MenuCrudPermissionService menuCrudPermissionService;

    @Autowired
    private RoleInfoService roleInfoService;

    @Autowired
    private MenuDefinitionService menuDefinitionService;
    
    @Override
    public void run(ApplicationArguments args) throws Exception {
        try {
            menuDefinitionService.initializeDefaultMenuDefinitions();
            menuPermissionService.initializeDefaultPermissions();
            menuCrudPermissionService.initializeDefaultCrudPermissions();
            roleInfoService.initializeDefaultRoles();
        } catch (Exception e) {
            System.out.println("권한 초기화 중 오류 발생: " + e.getMessage());
        }
    }
}
