package com.hyunchang.webapp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 블랙리스트 처리된 JWT의 jti(고유 식별자) 저장소.
 * 로그아웃 시 access_token과 refresh_token의 jti를 등록하면
 * 이후 해당 토큰의 인증/리프레시는 거부된다.
 *
 * expiresAt을 두어 만료된 항목은 주기적으로 정리한다.
 */
@Entity
@Table(name = "revoked_tokens", indexes = {
        @Index(name = "idx_revoked_jti", columnList = "jti", unique = true),
        @Index(name = "idx_revoked_expires_at", columnList = "expires_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RevokedToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String jti;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public RevokedToken(String jti, Instant expiresAt) {
        this.jti = jti;
        this.expiresAt = expiresAt;
    }
}
