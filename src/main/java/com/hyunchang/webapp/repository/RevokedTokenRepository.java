package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.RevokedToken;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RevokedTokenRepository extends JpaRepository<RevokedToken, Long> {

    boolean existsByJti(String jti);

    @Modifying
    @Query("delete from RevokedToken r where r.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);

    /**
     * 동시 logout 요청에서 동일 jti가 두 번 INSERT 시도되는 경쟁 상황 처리. existsByJti + save 조합은 TOCTOU 레이스가 있고,
     * DataIntegrityViolationException 발생 시 Hibernate가 트랜잭션을 rollback-only로 표시해 catch가 무력화된다. MySQL
     * INSERT IGNORE 로 DB 레벨에서 멱등성을 보장.
     */
    @Modifying
    @Query(
            value = "INSERT IGNORE INTO revoked_tokens (jti, expires_at) VALUES (:jti, :expiresAt)",
            nativeQuery = true)
    int insertIgnore(@Param("jti") String jti, @Param("expiresAt") Instant expiresAt);
}
