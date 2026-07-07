package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.dto.ChatMessage;
import com.hyunchang.webapp.dto.ChatRequest;
import com.hyunchang.webapp.dto.ChatResponse;
import com.hyunchang.webapp.entity.ChatRecord;
import com.hyunchang.webapp.entity.ChatSession;
import com.hyunchang.webapp.exception.ClaudeApiException;
import com.hyunchang.webapp.exception.ForbiddenException;
import com.hyunchang.webapp.service.ChatHistoryService;
import com.hyunchang.webapp.service.ClaudeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@RestController
@RequestMapping("/api")
public class ChatController {

    private final ClaudeService claudeService;
    private final ChatHistoryService chatHistoryService;

    // SSE 스트리밍 업스트림 호출용 별도 스레드 풀
    // (SseEmitter는 controller 스레드를 즉시 풀어줘야 하므로 비동기 실행 필요)
    private final ExecutorService streamingExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "chat-stream");
        t.setDaemon(true);
        return t;
    });

    public ChatController(ClaudeService claudeService, ChatHistoryService chatHistoryService) {
        this.claudeService = claudeService;
        this.chatHistoryService = chatHistoryService;
    }

    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request, Authentication authentication) {
        // SecurityConfig가 인증 강제하지만 방어적으로 한 번 더 체크
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String username = authentication.getName();

        String sessionKey = (request.getSessionKey() != null && !request.getSessionKey().isBlank())
                ? request.getSessionKey()
                : UUID.randomUUID().toString();

        String preview = request.getContent() != null && request.getContent().length() > 50
                ? request.getContent().substring(0, 50) + "..."
                : request.getContent();
        log.info("[채팅] user={}, session={}, message={}", username, sessionKey, preview);

        // 기존 세션이면 본인 소유 검증
        ChatSession existing = chatHistoryService.findSession(sessionKey).orElse(null);
        if (existing != null && existing.getUsername() != null && !username.equals(existing.getUsername())) {
            throw new ForbiddenException("본인 세션에서만 메시지를 보낼 수 있습니다.");
        }

        ChatSession session = (existing != null) ? existing
                : chatHistoryService.getOrCreateSession(sessionKey, username, null);

        List<ChatRecord> history = chatHistoryService.getMessages(session);

        List<ChatMessage> messages = new ArrayList<>();
        history.forEach(r -> messages.add(new ChatMessage(r.getRole(), r.getContent())));
        messages.add(new ChatMessage("user", request.getContent()));

        try {
            String response = claudeService.chat(messages);
            chatHistoryService.saveMessage(session, "user", request.getContent());
            chatHistoryService.saveMessage(session, "assistant", response);
            return ResponseEntity.ok(new ChatResponse(response));
        } catch (ClaudeApiException e) {
            chatHistoryService.saveMessage(session, "user", request.getContent());
            return ResponseEntity.ok(new ChatResponse(e.getUserMessage()));
        }
    }

    // 스트리밍 채팅: SSE로 토큰을 한 글자씩 흘려보냄.
    // - SseEmitter는 컨트롤러 스레드를 즉시 반환하므로 업스트림 호출은 별도 스레드에서 실행.
    // - 응답이 완전히 수신된 후 DB 저장(사용자/어시스턴트 메시지 동시).
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatRequest request, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            // SecurityConfig가 먼저 막지만 안전망. 401과 동일한 효과를 위해 즉시 종료.
            SseEmitter emitter = new SseEmitter(0L);
            try {
                emitter.send(SseEmitter.event().name("error").data("{\"message\":\"Unauthorized\"}"));
            } catch (IOException ignored) {}
            emitter.complete();
            return emitter;
        }
        String username = authentication.getName();

        String sessionKey = (request.getSessionKey() != null && !request.getSessionKey().isBlank())
                ? request.getSessionKey()
                : UUID.randomUUID().toString();

        ChatSession existing = chatHistoryService.findSession(sessionKey).orElse(null);
        if (existing != null && existing.getUsername() != null && !username.equals(existing.getUsername())) {
            throw new ForbiddenException("본인 세션에서만 메시지를 보낼 수 있습니다.");
        }
        ChatSession session = (existing != null) ? existing
                : chatHistoryService.getOrCreateSession(sessionKey, username, null);

        List<ChatRecord> history = chatHistoryService.getMessages(session);
        List<ChatMessage> messages = new ArrayList<>();
        history.forEach(r -> messages.add(new ChatMessage(r.getRole(), r.getContent())));
        messages.add(new ChatMessage("user", request.getContent()));

        String preview = request.getContent() != null && request.getContent().length() > 50
                ? request.getContent().substring(0, 50) + "..."
                : request.getContent();
        log.info("[채팅 스트리밍] user={}, session={}, message={}", username, sessionKey, preview);

        SseEmitter emitter = new SseEmitter(120_000L);
        ChatSession finalSession = session;

        streamingExecutor.execute(() -> {
            StringBuilder accumulated = new StringBuilder();
            try {
                claudeService.chatStream(messages, delta -> {
                    accumulated.append(delta);
                    try {
                        emitter.send(SseEmitter.event().name("delta").data(Map.of("text", delta)));
                    } catch (IOException e) {
                        // 클라이언트가 끊긴 경우. 람다에서 체크 예외 못 던지니 런타임으로 래핑.
                        throw new RuntimeException(e);
                    }
                });

                // 정상 완료 시에만 user/assistant 메시지 함께 저장.
                chatHistoryService.saveMessage(finalSession, "user", request.getContent());
                chatHistoryService.saveMessage(finalSession, "assistant", accumulated.toString());

                emitter.send(SseEmitter.event().name("done").data(Map.of("sessionKey", sessionKey)));
                emitter.complete();
            } catch (ClaudeApiException e) {
                // API 오류여도 사용자 메시지는 기록(요청 흐름 일관성 + 디버깅)
                chatHistoryService.saveMessage(finalSession, "user", request.getContent());
                try {
                    emitter.send(SseEmitter.event().name("error").data(Map.of("message", e.getUserMessage())));
                    emitter.complete();
                } catch (IOException ignored) {
                    emitter.completeWithError(e);
                }
            } catch (Exception e) {
                log.warn("[채팅 스트리밍 실패] {}", e.getMessage());
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    @GetMapping("/chat/history/{sessionKey}")
    public ResponseEntity<List<ChatHistoryService.MessageDto>> getHistory(@PathVariable String sessionKey,
                                                                          Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String username = authentication.getName();
        ChatSession session = chatHistoryService.findSession(sessionKey).orElse(null);
        if (session == null) {
            return ResponseEntity.ok(List.of());
        }
        if (session.getUsername() == null || !username.equals(session.getUsername())) {
            throw new ForbiddenException("본인 세션의 히스토리만 조회할 수 있습니다.");
        }
        return ResponseEntity.ok(chatHistoryService.getHistory(sessionKey));
    }

    @GetMapping("/chat/sessions/{username}")
    public ResponseEntity<List<ChatHistoryService.SessionSummaryDto>> getSessions(@PathVariable String username,
                                                                                   Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !username.equals(authentication.getName())) {
            throw new ForbiddenException("본인 세션만 조회할 수 있습니다.");
        }
        log.info("[채팅세션 조회] user={}", username);
        return ResponseEntity.ok(chatHistoryService.getSessionsByUsername(username));
    }
}
