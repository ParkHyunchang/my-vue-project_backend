package com.hyunchang.webapp.service.ai;

import java.time.Instant;

/**
 * AiProvider 호출 결과. - success=true 면 text 가 채워짐. - rateLimited=true 면 retryAt 이 채워짐 (Retry-After 헤더
 * 기반). - 둘 다 아니면 일반 실패 (errorMessage).
 */
public record AiProviderResult(
        boolean success, boolean rateLimited, String text, Instant retryAt, String errorMessage) {
    public static AiProviderResult success(String text) {
        return new AiProviderResult(true, false, text, null, null);
    }

    public static AiProviderResult rateLimited(Instant retryAt) {
        return new AiProviderResult(false, true, null, retryAt, "rate limited");
    }

    public static AiProviderResult error(String message) {
        return new AiProviderResult(false, false, null, null, message);
    }
}
