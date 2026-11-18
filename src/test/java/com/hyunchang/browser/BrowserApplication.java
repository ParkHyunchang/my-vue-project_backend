package com.hyunchang.browser;

import com.hyunchang.webapp.WebApplication;
import com.hyunchang.webapp.config.ExpenseTableDropMigration;
import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.config.TimelineDescriptionMigration;
import com.hyunchang.webapp.service.StockService;
import com.hyunchang.webapp.service.ClaudeService;
import com.hyunchang.webapp.service.KrxOpenApiService;
import com.hyunchang.webapp.service.LandPriceService;
import com.hyunchang.webapp.service.RealEstateApiService;
import com.hyunchang.webapp.service.RealEstateNewsService;
import com.hyunchang.webapp.service.TravelGeocodeService;
import com.hyunchang.webapp.service.saju.SajuCalendarClient;
import com.hyunchang.webapp.service.UserService;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.sql.DataSource;
import org.mockito.Mockito;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.web.context.support.StandardServletEnvironment;

/** Same production components, without the application's scheduling import or external startup. */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration(exclude = {
    org.springframework.boot.autoconfigure.web.reactive.function.client.WebClientAutoConfiguration.class,
    org.springframework.boot.autoconfigure.web.reactive.function.client.ClientHttpConnectorAutoConfiguration.class
})
@EntityScan("com.hyunchang.webapp.entity")
@EnableJpaRepositories("com.hyunchang.webapp.repository")
@ComponentScan(
        basePackages = "com.hyunchang.webapp",
        excludeFilters =
                @ComponentScan.Filter(
                        type = FilterType.ASSIGNABLE_TYPE,
                        classes = {
                            WebApplication.class,
                            StockService.class,
                            ClaudeService.class,
                            KrxOpenApiService.class,
                            LandPriceService.class,
                            RealEstateApiService.class,
                            RealEstateNewsService.class,
                            TravelGeocodeService.class,
                            SajuCalendarClient.class,
                            ExpenseTableDropMigration.class,
                            TimelineDescriptionMigration.class
                        }))
@SuppressWarnings("removal")
public class BrowserApplication {
    public static void main(String[] args) throws Exception {
        if (!(System.getSecurityManager() instanceof BrowserNetworkGuard)) {
            throw new IllegalStateException("BrowserNetworkGuard must be installed at JVM startup");
        }
        if (!System.getProperty("os.name").startsWith("Windows")) {
            throw new IllegalStateException("This harness isolates Windows upload paths only");
        }
        preflight();
        if (args.length == 1 && "--guard-only".equals(args[0])) return;
        if (args.length != 0) throw new IllegalArgumentException("No application overrides allowed");

        var environment = new StandardServletEnvironment();
        environment.getPropertySources().remove("systemEnvironment");
        environment.getPropertySources().remove("systemProperties");
        environment.getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "browserIsolation",
                                Map.of(
                                        "spring.config.location",
                                        "classpath:/browser/application-browser.properties",
                                        "spring.profiles.active", "browser-test")));
        var app = new SpringApplication(BrowserApplication.class);
        app.setEnvironment(environment);
        app.setAddCommandLineProperties(false);
        app.run();
    }

    private static void preflight() throws Exception {
        var guard = System.getSecurityManager();
        expectBlocked(() -> guard.checkConnect("example.invalid", -1));
        expectBlocked(() -> guard.checkConnect("127.0.0.1", 3200));
        expectBlocked(() -> guard.checkListen(3200));
        expectBlocked(() -> guard.checkAccept("192.0.2.1", 12345));
        expectBlocked(() -> System.setSecurityManager(null));
        expectBlocked(() -> Files.readString(Path.of(".env")));
        try (var socket = new Socket()) {
            expectBlocked(() -> socket.connect(new InetSocketAddress("127.0.0.1", 9), 100));
        }
        try (var listener = new ServerSocket()) {
            listener.bind(new InetSocketAddress("127.0.0.1", 13200));
        }
        System.out.println("BROWSER_GUARD_OK: outbound/DNS/dotenv/other ports denied; 127.0.0.1:13200 bind allowed");
    }

    private static void expectBlocked(CheckedAction action) throws Exception {
        try {
            action.run();
        } catch (SecurityException expected) {
            return;
        }
        throw new IllegalStateException("Network isolation preflight did not block an action");
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
    }

    @Bean
    StockService stockService() {
        // This service performs network warm-up from @PostConstruct. No market data is simulated.
        return Mockito.mock(StockService.class);
    }

    // JDK HTTP clients create an internal selector socket on Windows even before a request.
    // Replace only those external boundaries; auth, menu, todos and their persistence stay real.
    @Bean
    ClaudeService claudeService() { return Mockito.mock(ClaudeService.class); }

    @Bean
    KrxOpenApiService krxOpenApiService() { return Mockito.mock(KrxOpenApiService.class); }

    @Bean
    LandPriceService landPriceService() { return Mockito.mock(LandPriceService.class); }

    @Bean
    RealEstateApiService realEstateApiService() { return Mockito.mock(RealEstateApiService.class); }

    @Bean
    RealEstateNewsService realEstateNewsService() { return Mockito.mock(RealEstateNewsService.class); }

    @Bean
    TravelGeocodeService travelGeocodeService() { return Mockito.mock(TravelGeocodeService.class); }

    @Bean
    SajuCalendarClient sajuCalendarClient() { return Mockito.mock(SajuCalendarClient.class); }

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> inboundWithoutSelectorSocket() {
        return factory -> factory.setProtocol("org.apache.coyote.http11.Http11Nio2Protocol");
    }

    @Bean
    RestTemplateCustomizer noExternalHttp() {
        return template ->
                template.setRequestFactory(
                        (uri, method) -> {
                            throw new IllegalStateException("BROWSER_ISOLATION: external HTTP disabled");
                        });
    }

    @Bean
    @org.springframework.context.annotation.Scope("prototype")
    org.springframework.web.reactive.function.client.WebClient.Builder isolatedWebClientBuilder() {
        return org.springframework.web.reactive.function.client.WebClient.builder()
                .exchangeFunction(request -> reactor.core.publisher.Mono.error(
                        new IllegalStateException("BROWSER_ISOLATION: broker HTTP disabled")));
    }

    @Bean
    ApplicationRunner validateIsolationAndSeed(
            org.springframework.context.ApplicationContext context,
            DataSource dataSource,
            KiwoomProperties kiwoom,
            UserService users) {
        return args -> {
            if (!context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).isEmpty()) {
                throw new IllegalStateException("Scheduling must not be registered");
            }
            if (kiwoom.isConfigured() || kiwoom.isTradeEnabled()
                    || kiwoom.getUs().isTradeEnabled() || kiwoom.getUs().isStrategyEnabled()) {
                throw new IllegalStateException("Trading configuration is not isolated");
            }
            try (var connection = dataSource.getConnection()) {
                if (!connection.getMetaData().getURL().startsWith("jdbc:h2:mem:browser_harness")) {
                    throw new IllegalStateException("Unexpected database");
                }
            }
            users.registerUser("browser-user", "Browser Test", "browser@example.invalid", null,
                    "BrowserTest123!", "USER");
            System.out.println("BROWSER_APP_READY: H2 memory; scheduling absent; trading disabled; local test user seeded");
        };
    }
}
