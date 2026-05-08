package com.hyunchang.webapp.service;

import com.hyunchang.webapp.config.JwtUtil;
import com.hyunchang.webapp.entity.RevokedToken;
import com.hyunchang.webapp.repository.RevokedTokenRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Date;

/**
 * Access/Refresh JWT를 httpOnly 쿠키로 발급/삭제하고
 * 블랙리스트(jti)로 무효화하는 서비스.
 *
 * 쿠키 정책:
 * - HttpOnly: true (JS에서 접근 불가)
 * - SameSite: Lax (CSRF 1차 방어)
 * - Secure: HTTPS 환경에서만 true (jwt.cookie-secure 설정)
 * - Path: /
 */
@Service
public class TokenService {

    public static final String ACCESS_COOKIE = "access_token";
    public static final String REFRESH_COOKIE = "refresh_token";

    private final JwtUtil jwtUtil;
    private final RevokedTokenRepository revokedTokenRepository;

    @Value("${jwt.cookie-secure:false}")
    private boolean cookieSecure;

    @Value("${jwt.cookie-domain:}")
    private String cookieDomain;

    public TokenService(JwtUtil jwtUtil, RevokedTokenRepository revokedTokenRepository) {
        this.jwtUtil = jwtUtil;
        this.revokedTokenRepository = revokedTokenRepository;
    }

    public String issueTokens(String username, HttpServletResponse response) {
        String accessToken = jwtUtil.generateAccessToken(username);
        String refreshToken = jwtUtil.generateRefreshToken(username);
        writeCookie(response, ACCESS_COOKIE, accessToken, jwtUtil.getAccessExpirationMillis() / 1000);
        writeCookie(response, REFRESH_COOKIE, refreshToken, jwtUtil.getRefreshExpirationMillis() / 1000);
        return accessToken;
    }

    public void rotateAccessToken(String username, HttpServletResponse response) {
        String accessToken = jwtUtil.generateAccessToken(username);
        writeCookie(response, ACCESS_COOKIE, accessToken, jwtUtil.getAccessExpirationMillis() / 1000);
    }

    public void clearTokens(HttpServletResponse response) {
        clearCookie(response, ACCESS_COOKIE);
        clearCookie(response, REFRESH_COOKIE);
    }

    public String readCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        for (Cookie c : request.getCookies()) {
            if (name.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }

    @Transactional
    public void revoke(String token) {
        if (token == null || token.isBlank()) return;
        try {
            String jti = jwtUtil.extractJti(token);
            Date exp = jwtUtil.extractExpiration(token);
            if (jti != null && exp != null && !revokedTokenRepository.existsByJti(jti)) {
                revokedTokenRepository.save(new RevokedToken(jti, exp.toInstant()));
            }
        } catch (Exception ignored) {
            // 잘못된 토큰은 그냥 무시
        }
    }

    public boolean isRevoked(String jti) {
        if (jti == null) return false;
        return revokedTokenRepository.existsByJti(jti);
    }

    /** 매시간 만료된 블랙리스트 항목 정리. */
    @Scheduled(fixedRate = 3_600_000L)
    @Transactional
    public void cleanupExpired() {
        revokedTokenRepository.deleteExpired(Instant.now());
    }

    private void writeCookie(HttpServletResponse response, String name, String value, long maxAgeSeconds) {
        StringBuilder header = new StringBuilder();
        header.append(name).append('=').append(value);
        header.append("; Path=/");
        header.append("; Max-Age=").append(maxAgeSeconds);
        header.append("; HttpOnly");
        header.append("; SameSite=Lax");
        if (cookieSecure) header.append("; Secure");
        if (cookieDomain != null && !cookieDomain.isBlank()) {
            header.append("; Domain=").append(cookieDomain);
        }
        response.addHeader("Set-Cookie", header.toString());
    }

    private void clearCookie(HttpServletResponse response, String name) {
        StringBuilder header = new StringBuilder();
        header.append(name).append('=');
        header.append("; Path=/");
        header.append("; Max-Age=0");
        header.append("; HttpOnly");
        header.append("; SameSite=Lax");
        if (cookieSecure) header.append("; Secure");
        if (cookieDomain != null && !cookieDomain.isBlank()) {
            header.append("; Domain=").append(cookieDomain);
        }
        response.addHeader("Set-Cookie", header.toString());
    }
}
