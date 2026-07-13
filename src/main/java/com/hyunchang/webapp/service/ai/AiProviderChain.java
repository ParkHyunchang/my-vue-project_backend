package com.hyunchang.webapp.service.ai;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * AI Provider 체인.
 *
 * <p>등록 순서대로 시도: 1. GeminiProvider 2. GroqProvider 3. CloudflareProvider
 *
 * <p>각 단계에서: - 키 미설정 → skip - rate-limit 메모리에 기록된 차단 시각 이전 → skip - 429 응답 → 차단 시각 등록 후 다음 단계로 -
 * 5xx/네트워크 실패 → 다음 단계로 (차단 등록은 안 함) - 성공 → return Success
 *
 * <p>모두 실패하면 AllBlocked 반환. UI 가 다음 가능 시각을 사용자에게 표시.
 */
@Service
public class AiProviderChain {
    private static final Logger log = LoggerFactory.getLogger(AiProviderChain.class);

    private final List<AiProvider> providers;
    private final RateLimitTracker rateLimitTracker;

    public AiProviderChain(
            GeminiProvider gemini,
            GroqProvider groq,
            CloudflareProvider cloudflare,
            RateLimitTracker rateLimitTracker) {
        // 체인 순서 (Gemini → Groq → Cloudflare)
        this.providers = List.of(gemini, groq, cloudflare);
        this.rateLimitTracker = rateLimitTracker;
    }

    /** 기본값(expectJson=true) — 여행/부동산 등 구조화 JSON 응답이 필요한 기존 호출부 호환용. */
    public ChainResult analyze(String prompt) {
        return analyze(prompt, true);
    }

    /**
     * expectJson=false 면 API 레벨 JSON 강제 없이 순수 텍스트/마크다운 응답을 요청한다 (주식·포트폴리오 자유리포트 — JSON 강제가 걸려 있으면
     * 모델이 마크다운 대신 JSON 객체로 응답한다).
     */
    public ChainResult analyze(String prompt, boolean expectJson) {
        return analyze(prompt, null, expectJson);
    }

    /**
     * compactPrompt: 입력 한도가 작은 provider(Groq 무료 TPM 등)로 폴백할 때 대신 보낼 축약 프롬프트. null 이면 항상 원본을 보낸다.
     * 원본이 provider 의 maxPromptChars() 를 넘고 compactPrompt 가 있으면 그 provider 에만 축약본을 보낸다 — 폴백 시 근거
     * 데이터가 줄어드는 대신 413 거부 없이 응답을 받는다.
     */
    public ChainResult analyze(String prompt, String compactPrompt, boolean expectJson) {
        for (AiProvider p : providers) {
            if (!p.isEnabled()) {
                log.debug("[AI/Chain] {} skip (disabled)", p.getName());
                continue;
            }
            if (rateLimitTracker.isBlocked(p.getName())) {
                log.debug(
                        "[AI/Chain] {} skip (rate-limited until {})",
                        p.getName(),
                        rateLimitTracker.getBlockedUntil(p.getName()));
                continue;
            }

            String effectivePrompt = prompt;
            if (compactPrompt != null
                    && p.maxPromptChars() > 0
                    && prompt.length() > p.maxPromptChars()) {
                effectivePrompt = compactPrompt;
                log.info(
                        "[AI/Chain] {} 입력 한도({}자) 초과 — 컴팩트 프롬프트({}자 → {}자)로 대체",
                        p.getName(),
                        p.maxPromptChars(),
                        prompt.length(),
                        compactPrompt.length());
            }

            log.info("[AI/Chain] {} 호출 시도", p.getName());
            AiProviderResult r = p.generate(effectivePrompt, expectJson);
            if (r.success()) {
                log.info("[AI/Chain] {} 응답 성공 ({}자)", p.getName(), r.text().length());
                return new ChainResult(
                        true, p.getName(), p.getModel(), r.text(), null, currentStatus());
            }
            if (r.rateLimited()) {
                rateLimitTracker.block(p.getName(), r.retryAt());
                continue;
            }
            // 일반 실패 → 다음 provider
        }

        log.warn("[AI/Chain] 모든 provider 차단 또는 실패");
        return new ChainResult(false, null, null, null, earliestRetryAt(), currentStatus());
    }

    private Instant earliestRetryAt() {
        Instant earliest = null;
        for (AiProvider p : providers) {
            if (!p.isEnabled()) continue;
            Instant until = rateLimitTracker.getBlockedUntil(p.getName());
            if (until == null) continue;
            if (earliest == null || until.isBefore(earliest)) earliest = until;
        }
        return earliest;
    }

    /** 현재 각 provider 의 활성/차단 상태 (UI 표시용). */
    private List<ProviderStatus> currentStatus() {
        List<ProviderStatus> result = new ArrayList<>();
        for (AiProvider p : providers) {
            Instant blockedUntil =
                    p.isEnabled() ? rateLimitTracker.getBlockedUntil(p.getName()) : null;
            result.add(
                    new ProviderStatus(
                            p.getName(),
                            p.getModel(),
                            p.isEnabled(),
                            blockedUntil != null,
                            blockedUntil));
        }
        return Collections.unmodifiableList(result);
    }

    public record ChainResult(
            boolean success,
            String providerName, // 성공 시 응답한 provider 이름
            String model, // 성공 시 모델명
            String text, // 성공 시 응답 본문
            Instant retryAt, // 실패 시 다음 가능 시각 (모든 provider 중 가장 이른 것)
            List<ProviderStatus> providersStatus) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("success", success);
            if (providerName != null) m.put("providerName", providerName);
            if (model != null) m.put("model", model);
            if (retryAt != null) m.put("retryAt", retryAt.toString());
            m.put("providersStatus", providersStatus);
            return m;
        }
    }

    public record ProviderStatus(
            String name, String model, boolean enabled, boolean blocked, Instant blockedUntil) {}
}
