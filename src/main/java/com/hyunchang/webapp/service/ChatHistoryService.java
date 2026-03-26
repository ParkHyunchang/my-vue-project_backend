package com.hyunchang.webapp.service;

import com.hyunchang.webapp.entity.ChatRecord;
import com.hyunchang.webapp.entity.ChatSession;
import com.hyunchang.webapp.repository.ChatRecordRepository;
import com.hyunchang.webapp.repository.ChatSessionRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ChatHistoryService {

    private final ChatSessionRepository sessionRepository;
    private final ChatRecordRepository recordRepository;

    public ChatHistoryService(ChatSessionRepository sessionRepository, ChatRecordRepository recordRepository) {
        this.sessionRepository = sessionRepository;
        this.recordRepository = recordRepository;
    }

    public ChatSession getOrCreateSession(String sessionKey, String username, String anonId) {
        return sessionRepository.findBySessionKey(sessionKey)
                .orElseGet(() -> sessionRepository.save(new ChatSession(sessionKey, username, anonId)));
    }

    public List<ChatRecord> getMessages(ChatSession session) {
        return recordRepository.findByChatSessionOrderByCreatedAtAsc(session);
    }

    public void saveMessage(ChatSession session, String role, String content) {
        if ("user".equals(role) && (session.getTitle() == null || session.getTitle().isBlank())) {
            String title = content.length() > 50 ? content.substring(0, 47) + "..." : content;
            session.setTitle(title);
        }
        recordRepository.save(new ChatRecord(session, role, content));
        session.touch();
        sessionRepository.save(session);
    }

    public List<MessageDto> getHistory(String sessionKey) {
        return sessionRepository.findBySessionKey(sessionKey)
                .map(session -> recordRepository.findByChatSessionOrderByCreatedAtAsc(session).stream()
                        .map(r -> new MessageDto(r.getRole(), r.getContent(), r.getCreatedAt()))
                        .toList())
                .orElse(List.of());
    }

    public List<SessionSummaryDto> getSessionsByUsername(String username) {
        return sessionRepository.findByUsernameOrderByUpdatedAtDesc(username).stream()
                .map(s -> new SessionSummaryDto(
                        s.getSessionKey(), s.getUsername(),
                        recordRepository.countByChatSession(s),
                        s.getUpdatedAt(), s.getTitle()))
                .toList();
    }

    public List<SessionSummaryDto> getSessionsByKeys(List<String> keys) {
        if (keys == null || keys.isEmpty()) return List.of();
        return sessionRepository.findBySessionKeyInOrderByUpdatedAtDesc(keys).stream()
                .map(s -> new SessionSummaryDto(
                        s.getSessionKey(), s.getUsername(),
                        recordRepository.countByChatSession(s),
                        s.getUpdatedAt(), s.getTitle()))
                .toList();
    }

    public List<SessionSummaryDto> getSessionsByAnonId(String anonId) {
        return sessionRepository.findByAnonIdOrderByUpdatedAtDesc(anonId).stream()
                .map(s -> new SessionSummaryDto(
                        s.getSessionKey(), s.getUsername(),
                        recordRepository.countByChatSession(s),
                        s.getUpdatedAt(), s.getTitle()))
                .toList();
    }

    public List<SessionSummaryDto> getAllSessionSummaries() {
        return sessionRepository.findAllByOrderByUpdatedAtDesc().stream()
                .map(s -> new SessionSummaryDto(
                        s.getSessionKey(), s.getUsername(),
                        recordRepository.countByChatSession(s),
                        s.getUpdatedAt(), s.getTitle()))
                .toList();
    }

    public record MessageDto(String role, String content, LocalDateTime createdAt) {}
    public record SessionSummaryDto(String sessionKey, String username, long messageCount, LocalDateTime lastActivity, String title) {}
}
