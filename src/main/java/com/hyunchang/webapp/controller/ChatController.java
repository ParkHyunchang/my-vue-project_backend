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
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api")
public class ChatController {

    private final ClaudeService claudeService;
    private final ChatHistoryService chatHistoryService;

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
