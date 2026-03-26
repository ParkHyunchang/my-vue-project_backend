package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.ChatRecord;
import com.hyunchang.webapp.entity.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatRecordRepository extends JpaRepository<ChatRecord, Long> {
    List<ChatRecord> findByChatSessionOrderByCreatedAtAsc(ChatSession chatSession);
    long countByChatSession(ChatSession chatSession);
}
