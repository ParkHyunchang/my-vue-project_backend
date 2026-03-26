package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {
    Optional<ChatSession> findBySessionKey(String sessionKey);
    List<ChatSession> findAllByOrderByUpdatedAtDesc();
    List<ChatSession> findByUsernameOrderByUpdatedAtDesc(String username);
    List<ChatSession> findByAnonIdOrderByUpdatedAtDesc(String anonId);
    List<ChatSession> findBySessionKeyInOrderByUpdatedAtDesc(Collection<String> sessionKeys);
}
