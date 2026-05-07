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
        String sessionKey = (request.getSessionKey() != null && !request.getSessionKey().isBlank())
                ? request.getSessionKey()
                : UUID.randomUUID().toString();

        // 인증된 사용자가 호출한 경우, body의 username은 무시하고 principal로 강제
        String resolvedUsername = (authentication != null && authentication.isAuthenticated())
                ? authentication.getName()
                : null;

        String preview = request.getContent() != null && request.getContent().length() > 50
                ? request.getContent().substring(0, 50) + "..."
                : request.getContent();
        log.info("[채팅] user={}, session={}, message={}", resolvedUsername, sessionKey, preview);

        ChatSession session = chatHistoryService.getOrCreateSession(sessionKey, resolvedUsername, request.getAnonId());

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
    public List<ChatHistoryService.MessageDto> getHistory(@PathVariable String sessionKey) {
        return chatHistoryService.getHistory(sessionKey);
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

    @PostMapping("/chat/sessions/by-keys")
    public ResponseEntity<List<ChatHistoryService.SessionSummaryDto>> getSessionsByKeys(@RequestBody List<String> keys,
                                                                                        Authentication authentication) {
        // 인증된 사용자만 조회 가능 (자신의 세션 키 목록만 query하는 형태이므로 인증으로 충분)
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(chatHistoryService.getSessionsByKeys(keys));
    }

    @GetMapping("/chat/sessions/anon/{anonId}")
    public List<ChatHistoryService.SessionSummaryDto> getAnonSessions(@PathVariable String anonId) {
        // anonId는 클라이언트가 자체 생성하는 불투명 식별자(localStorage). 인증 없이 자신의 세션 조회용.
        return chatHistoryService.getSessionsByAnonId(anonId);
    }
}
