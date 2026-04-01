package com.hyunchang.webapp.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.net.URI;

/**
 * UPLOAD_BASE_URL 설정 시 사진 요청을 서버로 302 리다이렉트.
 * NAS 마운트 없이 서버 사진을 그대로 볼 수 있음.
 */
@Controller
@ConditionalOnExpression("!'${app.upload-base-url:}'.isEmpty()")
public class UploadRedirectController {

    @Value("${app.upload-base-url}")
    private String uploadBaseUrl;

    @SuppressWarnings("null")
    @GetMapping("/uploads/images/**")
    public ResponseEntity<Void> redirect(HttpServletRequest request) {
        URI location = URI.create(uploadBaseUrl + request.getRequestURI());
        return ResponseEntity.status(HttpStatus.FOUND).location(location).build();
    }
}
