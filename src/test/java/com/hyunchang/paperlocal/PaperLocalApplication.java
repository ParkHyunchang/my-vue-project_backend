package com.hyunchang.paperlocal;

import com.hyunchang.webapp.config.UsPaperConfig;
import com.hyunchang.webapp.controller.paper.UsPaperController;
import com.hyunchang.webapp.controller.paper.UsPaperExceptionHandler;
import com.hyunchang.webapp.service.paper.UsPaperEngineService;
import com.hyunchang.webapp.service.paper.UsPaperMarketFeed;
import com.hyunchang.webapp.service.paper.UsPaperMarketService;
import com.hyunchang.webapp.service.paper.UsPaperScheduler;
import com.hyunchang.webapp.service.paper.UsPaperService;
import com.hyunchang.webapp.util.KiwoomUsMarketHours;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.context.support.StandardServletEnvironment;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Local-only paper host. No production application scan, credentials, configuration or database.
 */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@EnableMethodSecurity
@EnableScheduling
@EntityScan("com.hyunchang.webapp.entity.paper")
@EnableJpaRepositories("com.hyunchang.webapp.repository.paper")
@Import({
    UsPaperConfig.class,
    UsPaperController.class,
    UsPaperExceptionHandler.class,
    UsPaperService.class,
    UsPaperEngineService.class,
    UsPaperMarketService.class,
    UsPaperScheduler.class
})
public class PaperLocalApplication {
    private static Path runtime;
    private static String token;
    private static FileChannel processChannel;
    private static FileLock processLock;
    private static boolean fixture;

    public static void main(String[] args) throws Exception {
        if (args.length > 1 || (args.length == 1 && !args[0].equals("--fixture"))) {
            throw new IllegalArgumentException(
                    "Only --fixture is supported; no configuration overrides");
        }
        fixture = args.length == 1;
        runtime = Path.of("").toAbsolutePath().normalize();
        if (!runtime.getFileName().toString().equals(fixture ? "paper-fixture" : "paper-local")) {
            throw new IllegalStateException("Use the dedicated run-paper-local.ps1 launcher");
        }
        processChannel =
                FileChannel.open(
                        runtime.resolve("process.lock"),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE);
        processLock = processChannel.tryLock();
        if (processLock == null)
            throw new IllegalStateException("Paper host already owns this database");
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        Files.writeString(runtime.resolve("access-token"), token);
        Map<String, Object> properties = new HashMap<>();
        properties.put("spring.config.location", "optional:classpath:/paper-local-unused/");
        properties.put("spring.profiles.active", "paper-local-only");
        properties.put("server.address", "127.0.0.1");
        properties.put("server.port", "13220");
        properties.put(
                "spring.datasource.url",
                "jdbc:h2:file:"
                        + runtime.resolve("ledger").toString().replace('\\', '/')
                        + ";DB_CLOSE_ON_EXIT=FALSE");
        properties.put("spring.datasource.driver-class-name", "org.h2.Driver");
        properties.put("spring.datasource.username", "sa");
        properties.put("spring.datasource.password", "");
        properties.put("spring.jpa.hibernate.ddl-auto", "update");
        properties.put("spring.jpa.open-in-view", "false");
        properties.put("spring.sql.init.mode", "never");
        properties.put("spring.h2.console.enabled", "false");
        properties.put("us-paper.enabled", "true");
        properties.put("us-paper.scheduler-enabled", !fixture);
        properties.put("paper.local.fixture", fixture);
        properties.put("server.error.include-message", "always");
        properties.put("server.error.include-stacktrace", "never");
        properties.put("spring.main.banner-mode", "off");
        var environment = new StandardServletEnvironment();
        environment.getPropertySources().remove("systemEnvironment");
        environment.getPropertySources().remove("systemProperties");
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource("paperIsolation", properties));
        var app = new SpringApplication(PaperLocalApplication.class);
        app.setEnvironment(environment);
        app.setAddCommandLineProperties(false);
        app.run();
    }

    @Bean
    SecurityFilterChain paperSecurity(HttpSecurity http) throws Exception {
        OncePerRequestFilter localToken =
                new OncePerRequestFilter() {
                    @Override
                    protected void doFilterInternal(
                            HttpServletRequest request,
                            HttpServletResponse response,
                            FilterChain chain)
                            throws ServletException, IOException {
                        String supplied = request.getHeader("X-Paper-Token");
                        boolean loopback =
                                "127.0.0.1".equals(request.getRemoteAddr())
                                        || "::1".equals(request.getRemoteAddr());
                        boolean valid =
                                loopback
                                        && supplied != null
                                        && token != null
                                        && MessageDigest.isEqual(
                                                supplied.getBytes(
                                                        java.nio.charset.StandardCharsets.UTF_8),
                                                token.getBytes(
                                                        java.nio.charset.StandardCharsets.UTF_8));
                        if (!valid || !request.getRequestURI().startsWith("/api/us-paper/")) {
                            response.setStatus(401);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter()
                                    .write("{\"message\":\"Local paper authorization required\"}");
                            return;
                        }
                        var authentication =
                                new UsernamePasswordAuthenticationToken(
                                        "paper-local",
                                        null,
                                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                        try {
                            chain.doFilter(request, response);
                        } finally {
                            SecurityContextHolder.clearContext();
                        }
                    }
                };
        return http.csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth ->
                                auth.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR)
                                        .permitAll()
                                        .anyRequest()
                                        .hasRole("ADMIN"))
                .addFilterBefore(localToken, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    ApplicationRunner paperReady() {
        return args ->
                System.out.println(
                        "PAPER_APP_READY: 127.0.0.1:13220; "
                                + (fixture
                                        ? "FIXTURE, no market network"
                                        : "PUBLIC quotes, virtual orders only")
                                + "; database="
                                + runtime.resolve("ledger"));
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "paper.local.fixture", havingValue = "true")
    FixtureClock fixtureClock() {
        return new FixtureClock(runtime);
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "paper.local.fixture", havingValue = "true")
    UsPaperMarketFeed fixtureFeed(FixtureClock clock) {
        return () -> {
            clock.advance();
            String scenario =
                    Files.exists(runtime.resolve("scenario"))
                            ? Files.readString(runtime.resolve("scenario")).trim()
                            : "normal";
            if (scenario.equals("failure")) throw new IOException("FIXTURE feed failure");
            BigDecimal price = new BigDecimal(scenario.equals("drop") ? "75" : "100");
            List<UsPaperMarketFeed.DailyBar> bars = new ArrayList<>();
            LocalDate day = LocalDate.of(2026, 11, 17);
            while (bars.size() < 200) {
                if (KiwoomUsMarketHours.isTradingDay(day))
                    bars.add(
                            new UsPaperMarketFeed.DailyBar(
                                    day,
                                    new BigDecimal(
                                            day.equals(LocalDate.of(2026, 11, 17)) ? "95" : "90")));
                day = day.minusDays(1);
            }
            Collections.reverse(bars);
            Instant quotedAt =
                    scenario.equals("stale") ? clock.instant().minusSeconds(7200) : clock.instant();
            return new UsPaperMarketFeed.MarketSnapshot(
                    price, quotedAt, bars, "FIXTURE_NO_NETWORK");
        };
    }

    static final class FixtureClock extends Clock {
        private final Path sequence;
        private long step;

        FixtureClock(Path folder) {
            sequence = folder.resolve("quote-sequence");
            try {
                step =
                        Files.exists(sequence)
                                ? Long.parseLong(Files.readString(sequence).trim())
                                : 0;
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        }

        synchronized void advance() throws IOException {
            Files.writeString(sequence, Long.toString(++step));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public synchronized Instant instant() {
            return Instant.parse("2026-11-18T15:00:00Z").plusSeconds(step * 5);
        }
    }
}
