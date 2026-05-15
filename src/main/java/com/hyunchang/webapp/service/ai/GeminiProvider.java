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
    private static final String MODEL = "gemini-2.0-flash";

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
    public AiProviderResult generate(String prompt) {
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of(
                        "parts", List.of(Map.of("text", prompt))
                )),
                "generationConfig", Map.of(
                        "responseMimeType", "application/json",
                        "temperature", 0.4
                )
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
            if (status == 429) {
                Instant retryAt = parseRetryAfter(e, Duration.ofMinutes(1));
                log.warn("[AI/Gemini] 429 rate limited, retryAt={}", retryAt);
                return AiProviderResult.rateLimited(retryAt);
            }
            log.warn("[AI/Gemini] HTTP {} {}", status, truncate(e.getResponseBodyAsString(), 200));
            return AiProviderResult.error("Gemini HTTP " + status);
        } catch (Exception e) {
            log.warn("[AI/Gemini] 호출 실패: {}", e.getMessage());
            return AiProviderResult.error(e.getMessage());
        }
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
