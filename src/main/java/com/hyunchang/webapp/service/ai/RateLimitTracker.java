package com.hyunchang.webapp.service.ai;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provider별 rate-limit 상태 메모리 추적기.
 *
 * 429 응답에서 받은 Retry-After 시각까지는 해당 provider 를 skip 한다.
 * 서버 재시작 시 초기화 (DB 저장 X). 개인 프로젝트라 충분.
 */
@Component
public class RateLimitTracker {

    private final Map<String, Instant> blockedUntil = new ConcurrentHashMap<>();

    public boolean isBlocked(String providerName) {
        Instant until = blockedUntil.get(providerName);
        if (until == null) return false;
        if (Instant.now().isAfter(until)) {
            blockedUntil.remove(providerName);
            return false;
        }
        return true;
    }

    public Instant getBlockedUntil(String providerName) {
        Instant until = blockedUntil.get(providerName);
        if (until == null) return null;
        if (Instant.now().isAfter(until)) {
            blockedUntil.remove(providerName);
            return null;
        }
        return until;
    }

    public void block(String providerName, Instant until) {
        if (until == null) return;
        blockedUntil.put(providerName, until);
    }

    public void clear(String providerName) {
        blockedUntil.remove(providerName);
    }
}
