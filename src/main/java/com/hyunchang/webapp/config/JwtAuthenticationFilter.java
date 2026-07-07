package com.hyunchang.webapp.config;

import com.hyunchang.webapp.service.TokenService;
import com.hyunchang.webapp.service.UserService;
import io.jsonwebtoken.ExpiredJwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final UserService userService;
    private final TokenService tokenService;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        String jwt = resolveToken(request);
        String username = null;

        if (jwt != null) {
            try {
                username = jwtUtil.extractUsername(jwt);
            } catch (ExpiredJwtException e) {
                logger.warn("JWT token expired: " + e.getMessage());
            } catch (Exception e) {
                logger.error("JWT token parsing error", e);
            }
        }

        if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            UserDetails userDetails = userService.loadUserByUsername(username);

            if (jwtUtil.validateAccessToken(jwt, userDetails)
                    && !tokenService.isRevoked(safeJti(jwt))) {
                UsernamePasswordAuthenticationToken authToken =
                        new UsernamePasswordAuthenticationToken(
                                userDetails, null, userDetails.getAuthorities());
                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authToken);
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 쿠키 access_token이 우선이고, 없으면 Authorization Bearer 헤더로 폴백. 헤더 폴백은 점진적 마이그레이션을 위한 임시 호환성. 모든
     * 클라이언트가 쿠키로 전환되면 제거 가능.
     */
    private String resolveToken(HttpServletRequest request) {
        String fromCookie = tokenService.readCookie(request, TokenService.ACCESS_COOKIE);
        if (fromCookie != null && !fromCookie.isBlank()) {
            return fromCookie;
        }
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }

    private String safeJti(String token) {
        try {
            return jwtUtil.extractJti(token);
        } catch (Exception e) {
            return null;
        }
    }
}
