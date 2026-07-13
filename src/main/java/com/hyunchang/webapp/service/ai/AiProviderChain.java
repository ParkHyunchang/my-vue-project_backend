package com.hyunchang.webapp.service.ai;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * AI Provider 체인.
 *
 * <p>등록 순서대로 시도: 1. GeminiProvider 2. GeminiFlashLiteProvider 3. GroqProvider 4. CloudflareProvider
 *
 * <p>각 단계에서: - 키 미설정 → skip - rate-limit 메모리에 기록된 차단 시각 이전 → skip - 429 응답 → 차단 시각 등록 후 다음 단계로 -
 * 5xx/네트워크 실패 → 다음 단계로 (차단 등록은 안 함) - 성공 → return Success
 *
 * <p>모두 실패하면 AllBlocked 반환. UI 가 다음 가능 시각을 사용자에게 표시.
 */
@Service
public class AiProviderChain {
    private static final Logger log = LoggerFactory.getLogger(AiProviderChain.class);

    /**
     * 전체 체인(폴백 포함)의 end-to-end 예산. 리버스 프록시 기본 read timeout(60초)에서 요청 전처리(뉴스·재무·후보 수집, 최대 ~10초)를 뺀
     * 값보다 작게. 예산이 provider 하나의 소켓 타임아웃과 같으면 1차 provider 가 느린 순간 폴백 기회 없이 전체가 실패하므로 (2026-07 포트폴리오
     * 진단 장애) 반드시 provider 상한보다 크게 유지할 것.
     */
    private static final long ANALYSIS_TIMEOUT_MS = 45_000L;

    /**
     * provider 1개가 전체 예산을 독식하지 못하게 하는 상한. 가장 긴 provider 소켓 타임아웃(Gemini Flash: 연결 3초 + 읽기 30초)보다 살짝
     * 크게 잡아, 정상 경로에서는 provider 내부 read timeout 이 먼저 발동해 error 결과로 처리되고 이 값은 최후의 가드로만 동작한다.
     */
    private static final long PER_PROVIDER_TIMEOUT_MS = 35_000L;

    private final List<AiProvider> providers;
    private final RateLimitTracker rateLimitTracker;

    public AiProviderChain(
            // GeminiFlashLiteProvider 가 GeminiProvider 를 상속해 타입만으로는 빈이 2개 매칭됨 — 이름으로 고정
            @Qualifier("geminiProvider") GeminiProvider gemini,
            GeminiFlashLiteProvider geminiLite,
            GroqProvider groq,
            CloudflareProvider cloudflare,
            RateLimitTracker rateLimitTracker) {
        // 체인 순서 (Gemini Flash → Gemini Flash Lite → Groq → Cloudflare)
        // flash-lite 는 같은 키의 별도 용량 풀 — flash 혼잡(503) 시에도 전체 프롬프트 품질 유지용 2차
        this.providers = List.of(gemini, geminiLite, groq, cloudflare);
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
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ANALYSIS_TIMEOUT_MS);
        for (AiProvider p : providers) {
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remainingMs <= 0) {
                log.warn("[AI/Chain] analysis time budget exhausted");
                break;
            }
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
            final String requestPrompt = effectivePrompt;
            long waitMs = Math.min(remainingMs, PER_PROVIDER_TIMEOUT_MS);
            AiProviderResult r;
            CompletableFuture<AiProviderResult> future =
                    CompletableFuture.supplyAsync(() -> p.generate(requestPrompt, expectJson));
            try {
                r = future.get(waitMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                // 백그라운드 호출은 소켓 read 가 인터럽트되지 않아 자체 타임아웃까지 이어질 수 있지만
                // 결과는 버려진다 — 뒤늦게 남는 provider 로그는 이 케이스.
                future.cancel(true);
                log.warn(
                        "[AI/Chain] {} 이(가) {}초 내 응답하지 않아 다음 provider 로 폴백",
                        p.getName(),
                        waitMs / 1000);
                continue;
            } catch (Exception e) {
                log.warn("[AI/Chain] {} failed: {}", p.getName(), e.getMessage());
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                continue;
            }
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
