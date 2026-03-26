package com.hyunchang.webapp.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "chat_session")
@Getter
@NoArgsConstructor
public class ChatSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_key", nullable = false, unique = true, length = 100)
    private String sessionKey;

    @Column(length = 100)
    private String username;

    @Column(name = "anon_id", length = 100)
    private String anonId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(length = 100)
    private String title;

    public ChatSession(String sessionKey, String username, String anonId) {
        this.sessionKey = sessionKey;
        this.username = username;
        this.anonId = anonId;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void touch() {
        this.updatedAt = LocalDateTime.now();
    }

    public void setTitle(String title) {
        this.title = title;
    }
}
