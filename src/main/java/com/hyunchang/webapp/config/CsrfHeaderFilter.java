package com.hyunchang.webapp.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 쿠키 기반 인증 환경에서 CSRF 보호를 위한 더블 서브밋 패턴.
 *
 * SameSite=Lax 쿠키가 1차 방어선이지만, 추가로 상태 변경 요청(POST/PUT/DELETE/PATCH)에는
 * "X-Requested-With: XMLHttpRequest" 또는 Content-Type=application/json 헤더를 요구한다.
 *
 * 일반 폼 submit이나 이미지 태그를 통한 CSRF 공격은 이런 커스텀 헤더를 설정할 수 없어 차단된다.
 *
 * 다음 경로는 제외:
 * - /api/auth/login, /api/auth/register: 로그인 전이라 쿠키가 없음 (CSRF 위험 없음)
 * - /api/auth/refresh: 사전 쿠키 회전. 자체적으로 refresh_token 검증.
 * - GET/HEAD/OPTIONS: 부수효과 없음
 * - 멀티파트 업로드: 파일 업로드 폼은 별도 검증 필요 시 추가
 */
@Component
public class CsrfHeaderFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        String method = request.getMethod();
        if (isStateChanging(method) && !isExempt(request)) {
            String xrw = request.getHeader("X-Requested-With");
            String contentType = request.getContentType();
            boolean hasXhrHeader = "XMLHttpRequest".equalsIgnoreCase(xrw);
            boolean isJsonOrMultipart = contentType != null
                    && (contentType.startsWith("application/json")
                        || contentType.startsWith("multipart/form-data"));

            if (!hasXhrHeader && !isJsonOrMultipart) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"message\":\"CSRF protection: missing X-Requested-With header or unsupported content type.\"}");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean isStateChanging(String method) {
        return "POST".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method)
                || "DELETE".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method);
    }

    private boolean isExempt(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) return true;
        return path.equals("/api/auth/login")
                || path.equals("/api/auth/register")
                || path.equals("/api/auth/refresh")
                || path.startsWith("/h2-console");
    }
}
