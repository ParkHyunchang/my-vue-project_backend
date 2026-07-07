package com.hyunchang.webapp.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * /api/auth/** 엔드포인트에 대한 IP 기반 rate limit. 슬라이딩 윈도우(60초) 안에서 IP당 최대 30회 요청 허용. 초과 시 429 Too Many
 * Requests 응답.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final long WINDOW_MILLIS = 60_000L;
    private static final int MAX_REQUESTS_PER_WINDOW = 60;
    private static final String AUTH_PATH_PREFIX = "/api/auth/";

    private final ConcurrentHashMap<String, Deque<Long>> requestLog = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path != null && path.startsWith(AUTH_PATH_PREFIX) && !isExcluded(path)) {
            String key = clientKey(request);
            if (!allow(key)) {
                log.warn("[RATE_LIMIT] blocked path={}, key={}", path, key);
                response.setStatus(429);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"message\":\"요청이 너무 많습니다. 잠시 후 다시 시도해주세요.\"}");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private boolean isExcluded(String path) {
        // 인증된 사용자 본인의 정보 갱신 흐름은 제외 (rate limit 대상 X)
        return path.startsWith("/api/auth/me")
                || path.startsWith("/api/auth/menus")
                || path.startsWith("/api/auth/my-menu-permissions");
    }

    private boolean allow(String key) {
        long now = System.currentTimeMillis();
        Deque<Long> timestamps =
                requestLog.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
        synchronized (timestamps) {
            Iterator<Long> it = timestamps.iterator();
            while (it.hasNext()) {
                Long ts = it.next();
                if (ts == null || now - ts > WINDOW_MILLIS) {
                    it.remove();
                } else {
                    break;
                }
            }
            if (timestamps.size() >= MAX_REQUESTS_PER_WINDOW) {
                return false;
            }
            timestamps.addLast(now);
            return true;
        }
    }

    private String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return comma > 0 ? forwarded.substring(0, comma).trim() : forwarded.trim();
        }
        return request.getRemoteAddr();
    }
}
