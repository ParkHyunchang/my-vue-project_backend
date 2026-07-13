package com.hyunchang.webapp.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Groq + Llama 3.3 provider (2차). 무료 한도: 분 30회 / 일 14,400회 (모델별 차이)
 * https://console.groq.com/docs/rate-limits
 */
@Component
public class GroqProvider implements AiProvider {
    private static final Logger log = LoggerFactory.getLogger(GroqProvider.class);

    private static final String BASE_URL = "https://api.groq.com";
    private static final String MODEL = "llama-3.3-70b-versatile";
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String apiKey;

    public GroqProvider(@Value("${groq.api-key:}") String apiKey) {
        this.apiKey = apiKey;
        this.restClient = RestClient.builder().baseUrl(BASE_URL).build();
    }

    @Override
    public String getName() {
        return "Groq Llama";
    }

    @Override
    public String getModel() {
        return MODEL;
    }

    @Override
    public boolean isEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * llama-3.3-70b 무료 티어 TPM 12,000 — 한글 위주 프롬프트는 문자당 ~1.2 토큰이라 이 길이를 넘으면 입력+응답 합산이 한도를 초과해 413 으로
     * 거부된다 (종합 포트폴리오 진단이 해당). chain 이 컴팩트 프롬프트로 대체하는 기준.
     */
    @Override
    public int maxPromptChars() {
        return 8_000;
    }

    @Override
    public AiProviderResult generate(String prompt, boolean expectJson) {
        // expectJson=false(자유리포트 마크다운) 인 경우 API 레벨 JSON 강제를 걸지 않는다 —
        // 걸려 있으면 모델이 마크다운 대신 JSON 객체로 응답해 프론트 카드 UI가 깨진다.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", MODEL);
        body.put(
                "messages",
                List.of(
                        Map.of(
                                "role",
                                "system",
                                "content",
                                expectJson ? JSON_SYSTEM_MESSAGE : TEXT_SYSTEM_MESSAGE),
                        Map.of("role", "user", "content", prompt)));
        if (expectJson) body.put("response_format", Map.of("type", "json_object"));
        body.put("temperature", 0.4);

        try {
            String response =
                    restClient
                            .post()
                            .uri("/openai/v1/chat/completions")
                            .header("Authorization", "Bearer " + apiKey)
                            .header("Content-Type", "application/json")
                            .body(body)
                            .retrieve()
                            .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return AiProviderResult.error("응답에 choices 가 없습니다.");
            }
            String text = choices.get(0).path("message").path("content").asText();
            if (text.isBlank()) return AiProviderResult.error("응답 텍스트가 비어있습니다.");
            return AiProviderResult.success(text);

        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 429) {
                Instant retryAt = parseRetryAfter(e, Duration.ofMinutes(1));
                log.warn("[AI/Groq] 429 rate limited, retryAt={}", retryAt);
                return AiProviderResult.rateLimited(retryAt);
            }
            log.warn("[AI/Groq] HTTP {} {}", status, truncate(e.getResponseBodyAsString(), 200));
            return AiProviderResult.error("Groq HTTP " + status);
        } catch (Exception e) {
            log.warn("[AI/Groq] 호출 실패: {}", e.getMessage());
            return AiProviderResult.error(e.getMessage());
        }
    }

    private Instant parseRetryAfter(RestClientResponseException e, Duration fallback) {
        var headers = e.getResponseHeaders();
        if (headers != null) {
            try {
                List<String> values = headers.get("Retry-After");
                if (values != null && !values.isEmpty()) {
                    // Groq 는 보통 소수 초 단위 (예: "2.5") 반환
                    double seconds = Double.parseDouble(values.get(0).trim());
                    return Instant.now().plusMillis((long) (seconds * 1000));
                }
            } catch (Exception ignore) {
                /* fallthrough */
            }
        }
        return Instant.now().plus(fallback);
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
