package com.hyunchang.webapp.config;

import com.hyunchang.webapp.util.UploadPathUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private ActivityLogInterceptor activityLogInterceptor;

    @Value("${app.upload-base-url:}")
    private String uploadBaseUrl;

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
        if (!uploadBaseUrl.isEmpty()) {
            // UPLOAD_BASE_URL 설정 시: UploadRedirectController 가 리다이렉트 처리
            return;
        }
        registry.addResourceHandler("/uploads/images/**")
                .addResourceLocations("file:" + UploadPathUtil.imagesRoot())
                .setCachePeriod(3600);
    }

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        // 외부 API(Yahoo, Naver, Google translate, KRX 등) 호출 시 무한 대기 방지
        return builder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(15))
                .build();
    }

}