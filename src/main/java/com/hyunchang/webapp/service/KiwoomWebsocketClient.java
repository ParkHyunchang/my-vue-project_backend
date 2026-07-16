package com.hyunchang.webapp.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.config.KiwoomProperties;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/** 키움 서버 WebSocket을 받아 애플리케이션 내부 Flux(SSE)로 중계합니다. */
@Component
public class KiwoomWebsocketClient implements WebSocket.Listener {
    private final KiwoomProperties properties;
    private final KiwoomAuthService authService;
    private final ObjectMapper objectMapper;
    private final Sinks.Many<Map<String, Object>> events =
            Sinks.many().multicast().onBackpressureBuffer(500, false);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private volatile WebSocket socket;

    public KiwoomWebsocketClient(
            KiwoomProperties properties, KiwoomAuthService authService, ObjectMapper objectMapper) {
        this.properties = properties;
        this.authService = authService;
        this.objectMapper = objectMapper;
    }

    /** 연결 후 0B(주식체결) 구독을 등록합니다. 종목 코드는 서버에서만 관리해 프론트가 임의로 구독하지 못하게 합니다. */
    public void connectAndSubscribe(List<String> stockCodes) {
        if (connected.get()) return;
        authService
                .getAccessToken()
                .subscribe(
                        token ->
                                HttpClient.newHttpClient()
                                        .newWebSocketBuilder()
                                        .header("authorization", "Bearer " + token)
                                        .buildAsync(URI.create(properties.getWebsocketUrl()), this)
                                        .thenAccept(
                                                ws -> {
                                                    socket = ws;
                                                    connected.set(true);
                                                    sendRegistration(stockCodes);
                                                    publish("system", "키움 실시간 시세 연결됨");
                                                })
                                        .exceptionally(
                                                error -> {
                                                    publish(
                                                            "error",
                                                            "키움 WebSocket 연결 실패: "
                                                                    + error.getMessage());
                                                    return null;
                                                }));
    }

    public Flux<Map<String, Object>> events() {
        return events.asFlux();
    }

    public boolean isConnected() {
        return connected.get();
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        publish("market", data.toString());
        webSocket.request(1);
        return WebSocket.Listener.super.onText(webSocket, data, last);
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        webSocket.request(1);
        WebSocket.Listener.super.onOpen(webSocket);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        connected.set(false);
        publish("system", "키움 실시간 시세 연결 종료: " + statusCode);
        return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        connected.set(false);
        publish("error", "키움 WebSocket 오류: " + error.getMessage());
    }

    private void sendRegistration(List<String> stockCodes) {
        if (socket == null || stockCodes == null || stockCodes.isEmpty()) return;
        List<Map<String, String>> items =
                stockCodes.stream().map(code -> Map.of("jmcode", code)).toList();
        Map<String, Object> request =
                Map.of(
                        "trnm",
                        "REG",
                        "grp_no",
                        "1",
                        "refresh",
                        "0",
                        "data",
                        List.of(Map.of("item", items, "type", "0B")));
        try {
            socket.sendText(objectMapper.writeValueAsString(request), true);
        } catch (JsonProcessingException e) {
            publish("error", "실시간 구독 요청 생성 실패");
        }
    }

    private void publish(String type, String message) {
        events.tryEmitNext(
                Map.of("type", type, "message", message, "at", System.currentTimeMillis()));
    }

    @PreDestroy
    public void close() {
        if (socket != null) socket.sendClose(WebSocket.NORMAL_CLOSURE, "application shutdown");
    }
}
