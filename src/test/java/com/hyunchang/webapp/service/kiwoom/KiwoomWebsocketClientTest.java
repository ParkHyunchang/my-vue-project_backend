package com.hyunchang.webapp.service.kiwoom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.service.KiwoomAuthService;
import java.net.http.WebSocket;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class KiwoomWebsocketClientTest {
    private final KiwoomProperties properties = new KiwoomProperties();
    private final KiwoomAuthService auth = mock(KiwoomAuthService.class);
    private final KiwoomWebsocketClient.WebSocketConnector connector =
            mock(KiwoomWebsocketClient.WebSocketConnector.class);
    private KiwoomWebsocketClient client;

    @BeforeEach
    void setUp() {
        when(auth.getAccessToken()).thenReturn(Mono.just("token"));
        client = new KiwoomWebsocketClient(properties, auth, new ObjectMapper(), connector);
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    @Test
    void loginAloneIsNotConnectedUntilSubscriptionIsConfirmed() {
        WebSocket socket = socket();
        CompletableFuture<WebSocket> connection = new CompletableFuture<>();
        when(connector.connect(any(), eq("token"), any())).thenReturn(connection);

        client.connectAndSubscribe(List.of("005930"));
        client.connectAndSubscribe(List.of("005930"));

        verify(connector, times(1)).connect(any(), eq("token"), any());
        assertEquals(KiwoomWebsocketClient.ConnectionState.CONNECTING, client.connectionState());

        connection.complete(socket);
        assertEquals(
                KiwoomWebsocketClient.ConnectionState.AUTHENTICATING, client.connectionState());
        assertFalse(client.isConnected());

        client.handleMessage("{\"trnm\":\"LOGIN\",\"return_code\":0}");
        assertFalse(client.isConnected());

        client.handleMessage("{\"trnm\":\"REG\",\"return_code\":0}");
        assertTrue(client.isConnected());
        verify(socket, times(2)).sendText(anyString(), eq(true));
    }

    @Test
    void closeReconnectsOnceAndResubscribesExistingCodes() {
        WebSocket firstSocket = socket();
        WebSocket secondSocket = socket();
        CompletableFuture<WebSocket> first = CompletableFuture.completedFuture(firstSocket);
        CompletableFuture<WebSocket> second = CompletableFuture.completedFuture(secondSocket);
        when(connector.connect(any(), eq("token"), any())).thenReturn(first, second);

        client.connectAndSubscribe(List.of("005930", "000660"));
        client.handleMessage("{\"trnm\":\"LOGIN\",\"return_code\":0}");
        client.handleMessage("{\"trnm\":\"REG\",\"return_code\":0}");
        assertTrue(client.isConnected());

        client.onClose(firstSocket, 1006, "연결 끊김");
        assertFalse(client.isConnected());
        client.runScheduledReconnect();
        client.runScheduledReconnect();

        verify(connector, times(2)).connect(any(), eq("token"), any());
        client.handleMessage("{\"trnm\":\"LOGIN\",\"return_code\":0}");
        client.handleMessage("{\"trnm\":\"REG\",\"return_code\":0}");
        assertTrue(client.isConnected());
        verify(secondSocket, times(2)).sendText(anyString(), eq(true));
    }

    private WebSocket socket() {
        WebSocket socket = mock(WebSocket.class);
        when(socket.sendText(anyString(), eq(true)))
                .thenReturn(CompletableFuture.completedFuture(socket));
        when(socket.sendClose(anyInt(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(socket));
        return socket;
    }
}
