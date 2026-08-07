package com.hyunchang.webapp.service.kiwoom;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.service.KiwoomAuthService;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/** 키움 서버 WebSocket을 받아 애플리케이션 내부 Flux(SSE)로 중계합니다. */
@Component
public class KiwoomWebsocketClient implements WebSocket.Listener {
    private static final Logger log = LoggerFactory.getLogger(KiwoomWebsocketClient.class);
    private static final int[] RECONNECT_DELAYS_SECONDS = {1, 2, 5, 10, 30};

    private final KiwoomProperties properties;
    private final KiwoomAuthService authService;
    private final ObjectMapper objectMapper;
    private final WebSocketConnector connector;
    private final Sinks.Many<Map<String, Object>> events =
            Sinks.many().multicast().onBackpressureBuffer(500, false);
    private final AtomicReference<ConnectionState> connectionState =
            new AtomicReference<>(ConnectionState.DISCONNECTED);
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempt = new AtomicInteger(0);
    private final ScheduledExecutorService reconnectExecutor =
            Executors.newSingleThreadScheduledExecutor(
                    runnable -> {
                        Thread thread = new Thread(runnable, "kiwoom-websocket-reconnect");
                        thread.setDaemon(true);
                        return thread;
                    });
    private final java.util.Set<String> subscribedCodes = ConcurrentHashMap.newKeySet();
    private final List<Consumer<PriceTick>> priceListeners = new CopyOnWriteArrayList<>();
    private volatile WebSocket socket;
    private volatile boolean shuttingDown;

    @Autowired
    public KiwoomWebsocketClient(
            KiwoomProperties properties, KiwoomAuthService authService, ObjectMapper objectMapper) {
        this(
                properties,
                authService,
                objectMapper,
                (url, token, listener) ->
                        HttpClient.newHttpClient()
                                .newWebSocketBuilder()
                                .header("authorization", "Bearer " + token)
                                .buildAsync(URI.create(url), listener));
    }

    KiwoomWebsocketClient(
            KiwoomProperties properties,
            KiwoomAuthService authService,
            ObjectMapper objectMapper,
            WebSocketConnector connector) {
        this.properties = properties;
        this.authService = authService;
        this.objectMapper = objectMapper;
        this.connector = connector;
    }

    /** 연결 후 0B(주식체결) 구독을 등록합니다. 종목 코드는 서버에서만 관리해 프론트가 임의로 구독하지 못하게 합니다. */
    public void connectAndSubscribe(List<String> stockCodes) {
        addSubscriptionCodes(stockCodes);
        ConnectionState current = connectionState.get();
        if (current == ConnectionState.SUBSCRIBED) {
            sendRegistration();
            return;
        }
        if (current == ConnectionState.CONNECTING || current == ConnectionState.AUTHENTICATING)
            return;
        startConnection();
    }

    private void startConnection() {
        if (shuttingDown
                || !connectionState.compareAndSet(
                        ConnectionState.DISCONNECTED, ConnectionState.CONNECTING)) return;
        reconnectScheduled.set(false);
        log.info(
                "[자동매매][실시간 시세 연결 시도] 구독 대상={}종목, 재시도={}회",
                subscribedCodes.size(),
                reconnectAttempt.get());
        authService
                .getAccessToken()
                .subscribe(
                        token ->
                                connector
                                        .connect(properties.getWebsocketUrl(), token, this)
                                        .orTimeout(10, TimeUnit.SECONDS)
                                        .thenAccept(
                                                ws -> {
                                                    socket = ws;
                                                    connectionState.set(
                                                            ConnectionState.AUTHENTICATING);
                                                    log.info("[자동매매][실시간 시세 서버 연결] 상태=로그인 확인 대기");
                                                    sendLogin(token);
                                                    scheduleAuthenticationTimeout(ws);
                                                })
                                        .exceptionally(
                                                error -> {
                                                    handleConnectionFailure(
                                                            "키움 WebSocket 연결 실패", error);
                                                    return null;
                                                }),
                        error -> handleConnectionFailure("키움 인증 토큰 조회 실패", error));
    }

    public Flux<Map<String, Object>> events() {
        return events.asFlux();
    }

    public boolean isConnected() {
        return connectionState.get() == ConnectionState.SUBSCRIBED;
    }

    ConnectionState connectionState() {
        return connectionState.get();
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
        if (webSocket != socket)
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        socket = null;
        connectionState.set(ConnectionState.DISCONNECTED);
        log.warn(
                "[자동매매][실시간 시세 연결 종료] 상태코드={}, 사유={}, 처리=자동 재연결 예약",
                statusCode,
                reason == null || reason.isBlank() ? "없음" : reason);
        publish("system", "키움 실시간 시세 연결 종료: " + statusCode);
        scheduleReconnect();
        return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        if (webSocket != socket) return;
        socket = null;
        handleConnectionFailure("키움 WebSocket 오류", error);
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
                            objectMapper.writeValueAsString(
                                    Map.of("trnm", "LOGIN", "token", token)),
                            true)
                    .exceptionally(
                            error -> {
                                handleConnectionFailure("키움 실시간 로그인 요청 전송 실패", error);
                                return null;
                            });
        } catch (JsonProcessingException e) {
            handleConnectionFailure("키움 실시간 로그인 요청 생성 실패", e);
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
            WebSocket currentSocket = socket;
            currentSocket
                    .sendText(objectMapper.writeValueAsString(request), true)
                    .whenComplete(
                            (ignored, error) -> {
                                if (error != null) {
                                    handleConnectionFailure("키움 실시간 종목 구독 요청 전송 실패", error);
                                } else {
                                    log.info(
                                            "[자동매매][실시간 시세 구독 요청] 대상={}종목, 상태=키움 확인 대기",
                                            subscribedCodes.size());
                                }
                            });
        } catch (JsonProcessingException e) {
            handleConnectionFailure("실시간 구독 요청 생성 실패", e);
        }
    }

    void handleMessage(String raw) {
        try {
            JsonNode node = objectMapper.readTree(raw);
            String trnm = node.path("trnm").asText();
            if ("PING".equalsIgnoreCase(trnm)) {
                if (socket != null) socket.sendText(raw, true);
                return;
            }
            if ("LOGIN".equalsIgnoreCase(trnm)) {
                if (node.path("return_code").asInt(-1) == 0) {
                    log.info("[자동매매][실시간 시세 로그인 성공] 상태=종목 구독 준비");
                    if (subscribedCodes.isEmpty()) markSubscribed();
                    else sendRegistration();
                } else {
                    disconnectAndRetry("키움 실시간 로그인 실패: " + node.path("return_msg").asText());
                }
                return;
            }
            if ("REG".equalsIgnoreCase(trnm)) {
                if (node.path("return_code").asInt(-1) == 0) markSubscribed();
                else disconnectAndRetry("키움 실시간 종목 구독 실패: " + node.path("return_msg").asText());
                return;
            }
            PriceTick tick = parsePriceTick(node);
            if (tick != null) {
                // 일부 키움 응답 환경에서는 REG 확인 패킷 없이 바로 체결 패킷이 내려온다.
                // 실제 시세 수신은 구독 성공의 가장 강한 증거이므로 정상 상태로 전환한다.
                if (connectionState.get() != ConnectionState.SUBSCRIBED) markSubscribed();
                for (Consumer<PriceTick> listener : priceListeners) listener.accept(tick);
            }
        } catch (Exception e) {
            log.debug("키움 실시간 패킷 해석 실패: {}", e.getMessage());
        }
        publish("market", raw);
    }

    private void markSubscribed() {
        connectionState.set(ConnectionState.SUBSCRIBED);
        reconnectAttempt.set(0);
        reconnectScheduled.set(false);
        log.info("[자동매매][실시간 손절 감시 연결 완료] 구독={}종목, 상태=정상", subscribedCodes.size());
        publish("system", "키움 실시간 시세 연결 및 종목 구독 완료");
    }

    private void handleConnectionFailure(String message, Throwable error) {
        String reason = error == null || error.getMessage() == null ? "알 수 없음" : error.getMessage();
        connectionState.set(ConnectionState.DISCONNECTED);
        log.error("[자동매매][실시간 시세 연결 실패] 단계={}, 사유={}", message, reason);
        publish("error", message + ": " + reason);
        scheduleReconnect();
    }

    private void disconnectAndRetry(String message) {
        connectionState.set(ConnectionState.DISCONNECTED);
        WebSocket current = socket;
        socket = null;
        if (current != null) current.sendClose(WebSocket.NORMAL_CLOSURE, "reconnect");
        log.error("[자동매매][실시간 시세 인증 실패] {}, 처리=자동 재연결 예약", message);
        publish("error", message);
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (shuttingDown
                || subscribedCodes.isEmpty()
                || !reconnectScheduled.compareAndSet(false, true)) return;
        int attempt = reconnectAttempt.getAndIncrement();
        int delay =
                RECONNECT_DELAYS_SECONDS[Math.min(attempt, RECONNECT_DELAYS_SECONDS.length - 1)];
        log.warn("[자동매매][실시간 시세 재연결 예약] {}초 후 재시도, 대상={}종목", delay, subscribedCodes.size());
        reconnectExecutor.schedule(this::runScheduledReconnect, delay, TimeUnit.SECONDS);
    }

    private void scheduleAuthenticationTimeout(WebSocket expectedSocket) {
        reconnectExecutor.schedule(
                () -> {
                    if (!shuttingDown
                            && socket == expectedSocket
                            && connectionState.get() == ConnectionState.AUTHENTICATING)
                        disconnectAndRetry("키움 실시간 로그인 또는 종목 구독 확인 시간 초과");
                },
                10,
                TimeUnit.SECONDS);
    }

    void runScheduledReconnect() {
        reconnectScheduled.set(false);
        startConnection();
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
        shuttingDown = true;
        reconnectExecutor.shutdownNow();
        connectionState.set(ConnectionState.DISCONNECTED);
        if (socket != null) socket.sendClose(WebSocket.NORMAL_CLOSURE, "application shutdown");
    }

    enum ConnectionState {
        DISCONNECTED,
        CONNECTING,
        AUTHENTICATING,
        SUBSCRIBED
    }

    @FunctionalInterface
    interface WebSocketConnector {
        CompletableFuture<WebSocket> connect(String url, String token, WebSocket.Listener listener);
    }
}
