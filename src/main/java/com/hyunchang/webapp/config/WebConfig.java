package com.hyunchang.webapp.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private ActivityLogInterceptor activityLogInterceptor;

    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        registry.addInterceptor(activityLogInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/api/auth/check-username/**",
                        "/api/auth/check-email/**"
                );
    }

    @Override
    public void addResourceHandlers(@NonNull ResourceHandlerRegistry registry) {
        String uploadPath = getUploadDirectory();
        // 이미지 파일들을 위한 정적 리소스 핸들러
        // /uploads/images/** 패턴으로 dating/과 history/ 하위 디렉토리 모두 포함
        registry.addResourceHandler("/uploads/images/**")
                .addResourceLocations("file:" + uploadPath)
                .setCachePeriod(3600);
    }
    
    private String getUploadDirectory() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("linux") || os.contains("unix")) {
            // Docker 환경에서는 실제 NAS 경로 사용
            return "/volume1/docker/my-vue-project_backend/uploads/images/";
        } else {
            // Windows 환경 (로컬 개발)
            return System.getProperty("user.dir") + "/uploads/images/";
        }
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

}