package com.hyunchang.webapp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")  // 모든 경로에 대해 CORS 허용
                .allowedOriginPatterns("*")  // allowedOrigins 대신 allowedOriginPatterns 사용
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("X-Total-Count")  // X-Total-Count 헤더 노출
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 업로드된 이미지 파일을 정적 리소스로 서빙
        String uploadPath = getUploadDirectory();
        registry.addResourceHandler("/uploads/images/**")
                .addResourceLocations("file:" + uploadPath)
                .setCachePeriod(3600); // 1시간 캐시
    }
    
    private String getUploadDirectory() {
        // Docker 환경에서는 /app/uploads/images/ 사용, 로컬에서는 상대 경로 사용
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("linux") || os.contains("unix")) {
            // Linux/Unix 환경 (Docker 컨테이너)
            return "/app/uploads/images/";
        } else {
            // Windows 환경 (로컬 개발)
            return System.getProperty("user.dir") + "/uploads/images/";
        }
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(Arrays.asList("*"));
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("*"));
        configuration.setExposedHeaders(Arrays.asList("X-Total-Count"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
} 