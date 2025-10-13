package com.hyunchang.webapp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
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
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.asList(
            "http://localhost:8080", "http://localhost:3100", "http://localhost:3200", 
            "http://127.0.0.1:8080", "http://127.0.0.1:3100", "http://127.0.0.1:3200",
            "http://125.141.20.218:3100", "http://125.141.20.218:3200",
            "http://hyunchang.synology.me:3100", "http://hyunchang.synology.me:3200"
        ));
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "X-Requested-With", "Accept", "Origin", "Access-Control-Request-Method", "Access-Control-Request-Headers"));
        configuration.setExposedHeaders(Arrays.asList("X-Total-Count"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
} 