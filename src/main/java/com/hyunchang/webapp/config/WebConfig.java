package com.hyunchang.webapp.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")  // 모든 경로에 대해 CORS 허용
                .allowedOrigins(
                    "http://localhost:8080",      // 로컬 Vue 개발 서버
                    "http://localhost:3100",      // 로컬 Docker Vue
                    "http://localhost:3200",      // 현재 프론트엔드
                    "http://125.141.20.218:3100"  // NAS Vue HTTP
                )
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("X-Total-Count")  // X-Total-Count 헤더 노출
                .allowCredentials(true)
                .maxAge(3600);
    }
} 