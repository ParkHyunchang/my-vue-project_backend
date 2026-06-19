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
 * Cloudflare Workers AI provider (3차).
 * 무료 한도: 일 10,000 뉴런 (모델별 가중치 다름).
 * https://developers.cloudflare.com/workers-ai/platform/limits/
 *
 * 필요 환경변수: CLOUDFLARE_API_TOKEN, CLOUDFLARE_ACCOUNT_ID
 */
@Component
public class CloudflareProvider implements AiProvider {
    private static final Logger log = LoggerFactory.getLogger(CloudflareProvider.class);

    private static final String BASE_URL = "https://api.cloudflare.com";
    private static final String MODEL = "@cf/meta/llama-3.3-70b-instruct-fp8-fast";

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String apiToken;
    private final String accountId;

    public CloudflareProvider(
            @Value("${cloudflare.api-token:}") String apiToken,
            @Value("${cloudflare.account-id:}") String accountId) {
        this.apiToken = apiToken;
        this.accountId = accountId;
        this.restClient = RestClient.builder().baseUrl(BASE_URL).build();
    }

    @Override public String getName() { return "Cloudflare Workers AI"; }
    @Override public String getModel() { return MODEL; }

    @Override
    public boolean isEnabled() {
        return apiToken != null && !apiToken.isBlank()
                && accountId != null && !accountId.isBlank();
    }

    @Override
    public AiProviderResult generate(String prompt) {
        Map<String, Object> body = Map.of(
                "messages", List.of(
                        Map.of("role", "system", "content", JSON_SYSTEM_MESSAGE),
                        Map.of("role", "user", "content", prompt)
                ),
                "temperature", 0.4
        );

        try {
            String response = restClient.post()
                    .uri("/client/v4/accounts/{accountId}/ai/run/{model}", accountId, MODEL)
                    .header("Authorization", "Bearer " + apiToken)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            // Cloudflare 응답 형식: { "result": { "response": "..." }, "success": true, ... }
            if (!root.path("success").asBoolean(false)) {
                String err = root.path("errors").toString();
                return AiProviderResult.error("Cloudflare 실패: " + truncate(err, 200));
            }
            String text = root.path("result").path("response").asText();
            if (text.isBlank()) return AiProviderResult.error("응답 텍스트가 비어있습니다.");
            return AiProviderResult.success(text);

        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 429) {
                Instant retryAt = parseRetryAfter(e, Duration.ofMinutes(5));
                log.warn("[AI/Cloudflare] 429 rate limited, retryAt={}", retryAt);
                return AiProviderResult.rateLimited(retryAt);
            }
            log.warn("[AI/Cloudflare] HTTP {} {}", status, truncate(e.getResponseBodyAsString(), 200));
            return AiProviderResult.error("Cloudflare HTTP " + status);
        } catch (Exception e) {
            log.warn("[AI/Cloudflare] 호출 실패: {}", e.getMessage());
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
