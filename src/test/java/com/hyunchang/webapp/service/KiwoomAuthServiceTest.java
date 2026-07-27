package com.hyunchang.webapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hyunchang.webapp.config.KiwoomProperties;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class KiwoomAuthServiceTest {

    @Test
    void concurrentTokenRequestsShareOneIssuance() {
        AtomicInteger issuanceCount = new AtomicInteger();
        WebClient.Builder clientBuilder =
                WebClient.builder()
                        .exchangeFunction(
                                request -> {
                                    issuanceCount.incrementAndGet();
                                    return Mono.delay(Duration.ofMillis(25))
                                            .thenReturn(
                                                    ClientResponse.create(HttpStatus.OK)
                                                            .header(
                                                                    "Content-Type",
                                                                    "application/json")
                                                            .body(
                                                                    "{\"token\":\"shared-token\","
                                                                            + "\"expires_dt\":\"20990101000000\","
                                                                            + "\"return_code\":0}")
                                                            .build());
                                });

        KiwoomAuthService service = new KiwoomAuthService(configuredProperties(), clientBuilder);

        List<String> tokens =
                Flux.merge(service.getAccessToken(), service.getAccessToken())
                        .collectList()
                        .block();

        assertEquals(List.of("shared-token", "shared-token"), tokens);
        assertEquals(1, issuanceCount.get());
    }

    private KiwoomProperties configuredProperties() {
        KiwoomProperties properties = new KiwoomProperties();
        properties.setAppKey("app-key");
        properties.setSecretKey("secret-key");
        properties.setAccountNo("12345678");
        return properties;
    }
}
