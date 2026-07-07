package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.ChatSession;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {
    Optional<ChatSession> findBySessionKey(String sessionKey);

    List<ChatSession> findAllByOrderByUpdatedAtDesc();

    List<ChatSession> findByUsernameOrderByUpdatedAtDesc(String username);
}
