package com.hyunchang.webapp.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class ActivityLogInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ActivityLogInterceptor.class);

    private static final String START_TIME_ATTR = "activityLog.startTime";

    // 폴링성·렌더링용 GET 요청은 활동 로그에서 제외 (소음 제거).
    // 사용자가 "행동했다"고 볼 만한 의미 있는 요청만 남기는 것이 목적.
    private static final List<String> NOISY_GET_PREFIXES =
            List.of(
                    "/api/stock/quote",
                    "/api/stock/search",
                    "/api/stock/krx-config",
                    "/api/stock/en-names",
                    "/api/stock/heatmap",
                    "/api/stock/top10",
                    "/api/public/",
                    "/api/auth/me",
                    "/api/auth/menus",
                    "/api/auth/my-menu-permissions",
                    "/uploads/");

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_TIME_ATTR, System.currentTimeMillis());
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception ex) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return;

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null
                || !auth.isAuthenticated()
                || "anonymousUser".equals(auth.getPrincipal())) {
            return;
        }

        String method = request.getMethod();
        String path = request.getRequestURI();

        // 감사 전용 엔드포인트는 자체적으로 [CATEGORY] VIEW 로그를 남기므로 [ACTION] 중복 기록 생략
        if (path.startsWith("/api/audit/")) return;

        if ("GET".equalsIgnoreCase(method) && isNoisyPath(path)) return;

        Long start = (Long) request.getAttribute(START_TIME_ATTR);
        long elapsed = start != null ? System.currentTimeMillis() - start : -1L;

        log.info(
                "[ACTION] user={}({}), {} {} → {} ({}ms)",
                auth.getName(),
                resolveRole(auth),
                method,
                path,
                response.getStatus(),
                elapsed);
    }

    private boolean isNoisyPath(String path) {
        for (String prefix : NOISY_GET_PREFIXES) {
            if (path.startsWith(prefix)) return true;
        }
        return false;
    }

    private String resolveRole(Authentication auth) {
        if (auth.getAuthorities() == null || auth.getAuthorities().isEmpty()) return "?";
        GrantedAuthority first = auth.getAuthorities().iterator().next();
        String authority = first.getAuthority();
        return authority.startsWith("ROLE_") ? authority.substring(5) : authority;
    }
}
