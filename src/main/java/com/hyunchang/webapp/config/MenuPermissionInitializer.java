package com.hyunchang.webapp.config;

import com.hyunchang.webapp.service.MenuPermissionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class MenuPermissionInitializer implements ApplicationRunner {
    
    @Autowired
    private MenuPermissionService menuPermissionService;
    
    @Override
    public void run(ApplicationArguments args) throws Exception {
        try {
            // 애플리케이션 시작 시 기본 메뉴 권한 초기화
            menuPermissionService.initializeDefaultPermissions();
        } catch (Exception e) {
            System.out.println("메뉴 권한 초기화 중 오류 발생: " + e.getMessage());
        }
    }
}
