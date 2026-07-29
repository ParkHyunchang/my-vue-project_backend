package com.hyunchang.webapp.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.config.KiwoomProperties;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
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
    private final java.util.Set<String> subscribedCodes = ConcurrentHashMap.newKeySet();
    private final List<Consumer<PriceTick>> priceListeners = new CopyOnWriteArrayList<>();
    private volatile WebSocket socket;

    public KiwoomWebsocketClient(
            KiwoomProperties properties, KiwoomAuthService authService, ObjectMapper objectMapper) {
        this.properties = properties;
        this.authService = authService;
        this.objectMapper = objectMapper;
    }

    /** 연결 후 0B(주식체결) 구독을 등록합니다. 종목 코드는 서버에서만 관리해 프론트가 임의로 구독하지 못하게 합니다. */
    public void connectAndSubscribe(List<String> stockCodes) {
        addSubscriptionCodes(stockCodes);
        if (connected.get()) {
            sendRegistration();
            return;
        }
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
                                                    sendLogin(token);
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

    /** Registers a listener for parsed 0B stock-execution ticks. Price checks stay in memory. */
    public void addPriceListener(Consumer<PriceTick> listener) {
        priceListeners.add(listener);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        String raw = data.toString();
        handleMessage(raw);
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

    private void addSubscriptionCodes(Collection<String> stockCodes) {
        if (stockCodes == null) return;
        stockCodes.stream()
                .filter(code -> code != null && code.matches("\\d{6}"))
                .forEach(subscribedCodes::add);
    }

    private void sendLogin(String token) {
        if (socket == null) return;
        try {
            socket.sendText(
                    objectMapper.writeValueAsString(Map.of("trnm", "LOGIN", "token", token)), true);
        } catch (JsonProcessingException e) {
            publish("error", "키움 실시간 로그인 요청 생성 실패");
        }
    }

    private void sendRegistration() {
        if (socket == null || subscribedCodes.isEmpty()) return;
        Map<String, Object> request =
                Map.of(
                        "trnm",
                        "REG",
                        "grp_no",
                        "1",
                        "refresh",
                        "1",
                        "data",
                        List.of(
                                Map.of(
                                        "item",
                                        new ArrayList<>(subscribedCodes),
                                        "type",
                                        List.of("0B"))));
        try {
            socket.sendText(objectMapper.writeValueAsString(request), true);
        } catch (JsonProcessingException e) {
            publish("error", "실시간 구독 요청 생성 실패");
        }
    }

    private void handleMessage(String raw) {
        try {
            JsonNode node = objectMapper.readTree(raw);
            String trnm = node.path("trnm").asText();
            if ("PING".equalsIgnoreCase(trnm)) {
                if (socket != null) socket.sendText(raw, true);
                return;
            }
            if ("LOGIN".equalsIgnoreCase(trnm)) {
                if (node.path("return_code").asInt(-1) == 0) {
                    sendRegistration();
                    publish("system", "키움 실시간 시세 연결됨");
                } else {
                    connected.set(false);
                    publish("error", "키움 실시간 로그인 실패: " + node.path("return_msg").asText());
                }
                return;
            }
            PriceTick tick = parsePriceTick(node);
            if (tick != null)
                for (Consumer<PriceTick> listener : priceListeners) listener.accept(tick);
        } catch (Exception ignored) {
            // A malformed market packet must never interrupt the real-time listener.
        }
        publish("market", raw);
    }

    private PriceTick parsePriceTick(JsonNode root) {
        JsonNode values = findValuesNode(root);
        if (values == null || !values.hasNonNull("10")) return null;
        String code = findCode(root);
        Long price = parseSignedLong(values.path("10").asText());
        return code == null || price == null || price <= 0 ? null : new PriceTick(code, price);
    }

    private JsonNode findValuesNode(JsonNode node) {
        if (node == null) return null;
        if (node.has("values") && node.path("values").isObject()) return node.path("values");
        if (node.isArray())
            for (JsonNode child : node) {
                JsonNode found = findValuesNode(child);
                if (found != null) return found;
            }
        if (node.isObject()) {
            var children = node.elements();
            while (children.hasNext()) {
                JsonNode found = findValuesNode(children.next());
                if (found != null) return found;
            }
        }
        return null;
    }

    private String findCode(JsonNode node) {
        if (node == null) return null;
        for (String field : List.of("stk_cd", "stock_code", "item", "code", "shcode")) {
            String value = node.path(field).asText("").replaceAll("[^0-9]", "");
            if (value.matches("\\d{6}")) return value;
        }
        if (node.isArray())
            for (JsonNode child : node) {
                String found = findCode(child);
                if (found != null) return found;
            }
        if (node.isObject()) {
            var children = node.elements();
            while (children.hasNext()) {
                String found = findCode(children.next());
                if (found != null) return found;
            }
        }
        return null;
    }

    private Long parseSignedLong(String raw) {
        try {
            return Math.abs(Long.parseLong(raw.replace(",", "").trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public void publishEvent(String type, String message) {
        events.tryEmitNext(
                Map.of("type", type, "message", message, "at", System.currentTimeMillis()));
    }

    private void publish(String type, String message) {
        publishEvent(type, message);
    }

    public record PriceTick(String stockCode, long price) {}

    @PreDestroy
    public void close() {
        if (socket != null) socket.sendClose(WebSocket.NORMAL_CLOSURE, "application shutdown");
    }
}
