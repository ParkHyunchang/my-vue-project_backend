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
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final UserService userService;
    private final TokenService tokenService;

    /**
     * Mono/Flux 반환 컨트롤러는 ASYNC 디스패치로 응답을 쓰는데, 이 필터는 그때 재실행되지 않는다. 요청 속성 저장소에 컨텍스트를 보존해야 ASYNC 디스패치의
     * 인가 검사가 인증 상태를 복원할 수 있다 (없으면 Mono 엔드포인트가 전부 401).
     */
    private final SecurityContextRepository securityContextRepository =
            new RequestAttributeSecurityContextRepository();

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
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authToken);
                SecurityContextHolder.setContext(context);
                securityContextRepository.saveContext(context, request, response);
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
