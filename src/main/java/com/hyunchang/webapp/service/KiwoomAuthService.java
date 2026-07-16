package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hyunchang.webapp.config.KiwoomProperties;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** 키움 OAuth 토큰을 메모리에 보관하고 만료 여유 시간 전에 재발급합니다. */
@Service
public class KiwoomAuthService {
    private static final DateTimeFormatter EXPIRES_AT_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private final KiwoomProperties properties;
    private final WebClient webClient;
    private volatile TokenState tokenState;

    public KiwoomAuthService(KiwoomProperties properties, WebClient.Builder webClientBuilder) {
        this.properties = properties;
        this.webClient = webClientBuilder.build();
    }

    public Mono<String> getAccessToken() {
        TokenState current = tokenState;
        if (current != null && current.isUsable(properties.getRefreshBeforeSeconds()))
            return Mono.just(current.token());
        return refreshToken().map(TokenState::token);
    }

    /** 운영자가 UI에서 누르는 수동 갱신용 메서드입니다. 토큰 원문은 절대 API 응답으로 노출하지 않습니다. */
    public synchronized Mono<TokenState> refreshToken() {
        if (!properties.isConfigured())
            return Mono.error(new IllegalStateException("키움 App Key/Secret Key가 설정되지 않았습니다."));
        return webClient
                .post()
                .uri(properties.getRestBaseUrl() + "/oauth2/token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                        Map.of(
                                "grant_type",
                                "client_credentials",
                                "appkey",
                                properties.getAppKey(),
                                "secretkey",
                                properties.getSecretKey()))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(this::toTokenState)
                .doOnNext(state -> tokenState = state);
    }

    public boolean hasUsableToken() {
        return tokenState != null && tokenState.isUsable(properties.getRefreshBeforeSeconds());
    }

    private TokenState toTokenState(JsonNode response) {
        String token = response.path("token").asText();
        if (token.isBlank() || response.path("return_code").asInt(0) != 0) {
            throw new IllegalStateException(
                    "키움 토큰 발급 실패: " + response.path("return_msg").asText("응답을 확인하세요."));
        }
        LocalDateTime expiresAt =
                LocalDateTime.parse(response.path("expires_dt").asText(), EXPIRES_AT_FORMAT);
        return new TokenState(token, expiresAt);
    }

    public record TokenState(String token, LocalDateTime expiresAt) {
        boolean isUsable(long refreshBeforeSeconds) {
            return expiresAt.isAfter(
                    LocalDateTime.now().plus(Duration.ofSeconds(refreshBeforeSeconds)));
        }
    }
}
