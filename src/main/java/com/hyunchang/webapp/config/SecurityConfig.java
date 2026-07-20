package com.hyunchang.webapp.config;

import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimitFilter rateLimitFilter;
    private final CsrfHeaderFilter csrfHeaderFilter;
    private final CorsProperties corsProperties;

    /** 운영 환경(prod, docker, nas)에서는 Swagger / H2 콘솔 노출을 차단한다. 로컬 개발 모드에서만 true. */
    @Value("${spring.profiles.active:local}")
    private String activeProfile;

    public SecurityConfig(
            @Lazy JwtAuthenticationFilter jwtAuthenticationFilter,
            RateLimitFilter rateLimitFilter,
            CsrfHeaderFilter csrfHeaderFilter,
            CorsProperties corsProperties) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.rateLimitFilter = rateLimitFilter;
        this.csrfHeaderFilter = csrfHeaderFilter;
        this.corsProperties = corsProperties;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config)
            throws Exception {
        return config.getAuthenticationManager();
    }

    private boolean isLocalProfile() {
        return activeProfile == null || "local".equalsIgnoreCase(activeProfile);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(unauthorizedEntryPoint()))
                .authorizeHttpRequests(
                        authz -> {
                            authz.requestMatchers(
                                            org.springframework.http.HttpMethod.OPTIONS, "/**")
                                    .permitAll()
                                    .requestMatchers("/api/auth/**")
                                    .permitAll()
                                    .requestMatchers("/api/public/**")
                                    .permitAll()
                                    .requestMatchers("/uploads/images/**")
                                    .permitAll();
                            // /api/chat 및 /api/chat/history는 인증 필요 (anyRequest().authenticated()로
                            // 자동 처리됨)
                            // 익명 채팅 흐름은 더 이상 지원하지 않음 (Anthropic API 비용 보호)

                            // Swagger / OpenAPI / H2 콘솔: 로컬 개발 환경에서만 노출
                            if (isLocalProfile()) {
                                authz.requestMatchers(
                                                "/v3/api-docs/**",
                                                "/swagger-ui/**",
                                                "/swagger-ui.html",
                                                "/webjars/**",
                                                "/h2-console/**")
                                        .permitAll();
                            } else {
                                authz.requestMatchers(
                                                "/v3/api-docs/**",
                                                "/swagger-ui/**",
                                                "/swagger-ui.html",
                                                "/webjars/**",
                                                "/h2-console/**")
                                        .denyAll();
                            }

                            authz.requestMatchers("/api/admin/user-crud-permissions")
                                    .authenticated()
                                    .requestMatchers("/api/admin/**")
                                    .hasRole("ADMIN")
                                    // 키움 실계좌 API: 잔고 조회(summary)·체결 이벤트(events)도 실계좌
                                    // 정보라 일반 로그인 사용자에게 노출하면 안 됨
                                    .requestMatchers("/api/kiwoom/**")
                                    .hasRole("ADMIN")
                                    .requestMatchers("/api/premium/**")
                                    .hasAnyRole("ADMIN", "PREMIUM")
                                    .anyRequest()
                                    .authenticated();
                        })
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(csrfHeaderFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(
                        jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public AuthenticationEntryPoint unauthorizedEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write("{\"message\":\"Unauthorized\"}");
        };
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // allowCredentials=true 사용 시 origin은 정확히 매칭되어야 한다 (와일드카드 금지)
        configuration.setAllowedOrigins(corsProperties.getAllowedOriginsList());
        configuration.setAllowedMethods(
                Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(
                Arrays.asList(
                        "Authorization",
                        "Content-Type",
                        "X-Requested-With",
                        "Accept",
                        "Origin",
                        "Access-Control-Request-Method",
                        "Access-Control-Request-Headers"));
        configuration.setExposedHeaders(Arrays.asList("X-Total-Count"));
        // httpOnly 쿠키 전송을 위해 credentials 허용
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
