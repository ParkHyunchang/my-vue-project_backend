package com.hyunchang.webapp.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Google Gemini Flash provider (1차).
 * 무료 한도: 분 15회 / 일 1500회 (gemini-2.0-flash 기준)
 * https://ai.google.dev/gemini-api/docs/rate-limits
 */
@Component
public class GeminiProvider implements AiProvider {
    private static final Logger log = LoggerFactory.getLogger(GeminiProvider.class);

    private static final String BASE_URL = "https://generativelanguage.googleapis.com";
    // 2026-05 변경: gemini-2.0-flash 가 키 한도 이슈로 매번 429 — 최신 + 더 안정적인 모델로 교체
    private static final String MODEL = "gemini-2.5-flash";

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String apiKey;

    public GeminiProvider(@Value("${gemini.api-key:}") String apiKey) {
        this.apiKey = apiKey;
        this.restClient = RestClient.builder().baseUrl(BASE_URL).build();
    }

    @Override public String getName() { return "Gemini Flash"; }
    @Override public String getModel() { return MODEL; }
    @Override public boolean isEnabled() { return apiKey != null && !apiKey.isBlank(); }

    @Override
    public AiProviderResult generate(String prompt, boolean expectJson) {
        Map<String, Object> generationConfig = new LinkedHashMap<>();
        // expectJson=false(자유리포트 마크다운) 인 경우 API 레벨 JSON 강제를 걸지 않는다 —
        // 걸려 있으면 모델이 마크다운 대신 JSON 객체로 응답해 프론트 카드 UI가 깨진다.
        if (expectJson) generationConfig.put("responseMimeType", "application/json");
        generationConfig.put("temperature", 0.4);
        // 2.5 Flash 의 thinking 모드 — dynamic(-1)은 12~17초로 너무 느려서 제한.
        // 256: 4~6초 (살짝 떨어지지만 빠름), 512: 6~9초 (절충), -1: dynamic(원본).
        // 포트폴리오 진단 같은 큰 컨텍스트에서 응답 시간을 더 줄이려고 256으로 축소.
        // ※ dynamic thinking 모드로 되돌리려면 아래 한 줄만 주석 처리하면 된다.
        generationConfig.put("thinkingConfig", Map.of("thinkingBudget", 256));

        Map<String, Object> body = Map.of(
                "systemInstruction", Map.of(
                        "parts", List.of(Map.of("text", expectJson ? JSON_SYSTEM_MESSAGE : TEXT_SYSTEM_MESSAGE))
                ),
                "contents", List.of(Map.of(
                        "parts", List.of(Map.of("text", prompt))
                )),
                "generationConfig", generationConfig
        );

        try {
            String response = restClient.post()
                    .uri("/v1beta/models/{model}:generateContent?key={key}", MODEL, apiKey)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            JsonNode candidates = root.path("candidates");
            if (!candidates.isArray() || candidates.isEmpty()) {
                return AiProviderResult.error("응답에 candidate가 없습니다.");
            }
            String text = candidates.get(0).path("content").path("parts").get(0).path("text").asText();
            if (text.isBlank()) return AiProviderResult.error("응답 텍스트가 비어있습니다.");
            return AiProviderResult.success(text);

        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            String errSummary = formatErrorBody(e.getResponseBodyAsString());
            if (status == 429) {
                Instant retryAt = parseRetryAfter(e, Duration.ofMinutes(1));
                // 본문에 RESOURCE_EXHAUSTED / quota / billing 같은 reason 이 들어있어 진단에 결정적
                log.warn("[AI/Gemini] 429 rate limited, retryAt={}, reason={}", retryAt, errSummary);
                return AiProviderResult.rateLimited(retryAt);
            }
            if (status >= 500 && status < 600) {
                // 503(UNAVAILABLE)·502 등 일시적 서버 과부하 — 60초 backoff 등록해서 잠시 다른 provider 우선
                // (Retry-After 헤더가 있으면 그 값 우선, 없으면 기본 60초)
                Instant retryAt = parseRetryAfter(e, Duration.ofSeconds(60));
                log.warn("[AI/Gemini] HTTP {} (일시 장애) — backoff until {}: {}", status, retryAt, errSummary);
                return AiProviderResult.rateLimited(retryAt);
            }
            log.warn("[AI/Gemini] HTTP {} — {}", status, errSummary);
            return AiProviderResult.error("Gemini HTTP " + status);
        } catch (Exception e) {
            log.warn("[AI/Gemini] 호출 실패: {}", e.getMessage());
            return AiProviderResult.error(e.getMessage());
        }
    }

    /** Gemini 에러 응답 body 의 JSON 에서 status·message 만 추출 (로그 가독성↑). */
    private String formatErrorBody(String body) {
        if (body == null || body.isBlank()) return "(no body)";
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode err = root.path("error");
            String status = err.path("status").asText("");
            String message = err.path("message").asText("");
            if (!status.isBlank() || !message.isBlank()) {
                return (status.isBlank() ? "" : status + " — ") + truncate(message, 300);
            }
        } catch (Exception ignore) { /* fallthrough */ }
        return truncate(body, 300);
    }

    private Instant parseRetryAfter(RestClientResponseException e, Duration fallback) {
        var headers = e.getResponseHeaders();
        if (headers != null) {
            try {
                List<String> values = headers.get("Retry-After");
                if (values != null && !values.isEmpty()) {
                    long seconds = Long.parseLong(values.get(0).trim());
                    return Instant.now().plusSeconds(seconds);
                }
            } catch (Exception ignore) { /* fallthrough */ }
        }
        return Instant.now().plus(fallback);
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
