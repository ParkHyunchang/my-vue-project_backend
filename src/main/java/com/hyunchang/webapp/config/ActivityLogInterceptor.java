package com.hyunchang.webapp.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class ActivityLogInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ActivityLogInterceptor.class);

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response, Object handler) {

        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || "anonymousUser".equals(auth.getPrincipal())) {
            return true;
        }

        String userId = auth.getName();
        String method = request.getMethod();
        String path   = request.getRequestURI();
        String action = resolveAction(method);

        log.info("[ACTION] user={}, action={}, path={}", userId, action, path);

        return true;
    }

    private String resolveAction(String method) {
        return switch (method.toUpperCase()) {
            case "GET"          -> "READ";
            case "POST"         -> "CREATE";
            case "PUT", "PATCH" -> "UPDATE";
            case "DELETE"       -> "DELETE";
            default             -> method;
        };
    }
}
