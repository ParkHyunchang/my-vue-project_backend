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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Google Gemini Flash provider (1차). 무료 한도: 분 15회 / 일 1500회 (gemini-2.0-flash 기준)
 * https://ai.google.dev/gemini-api/docs/rate-limits
 */
@Component
public class GeminiProvider implements AiProvider {
    private static final Logger log = LoggerFactory.getLogger(GeminiProvider.class);

    private static final String BASE_URL = "https://generativelanguage.googleapis.com";

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String apiKey;
    private final String model;
    private final String name;
    private final int thinkingBudget;
    private final Duration readTimeout;

    // 2026-05 변경: gemini-2.0-flash 가 키 한도 이슈로 매번 429 — 최신 + 더 안정적인 모델로 교체.
    // read timeout 30초: maxOutputTokens 5120 기준 최악 생성이 ~26초(실측 약 200 tok/s)라 그보다 여유.
    // 비스트리밍이라 생성이 끝날 때까지 첫 바이트가 안 오므로 이 값이 사실상 생성 시간 상한이다.
    // 생성자가 2개(스프링용 + 서브클래스용)라 @Autowired 로 스프링이 쓸 쪽을 명시해야 한다.
    @Autowired
    public GeminiProvider(@Value("${gemini.api-key:}") String apiKey) {
        this(apiKey, "gemini-2.5-flash", "Gemini Flash", 256, Duration.ofSeconds(30));
    }

    protected GeminiProvider(
            String apiKey, String model, String name, int thinkingBudget, Duration readTimeout) {
        this.apiKey = apiKey;
        this.model = model;
        this.name = name;
        this.thinkingBudget = thinkingBudget;
        this.readTimeout = readTimeout;
        this.restClient =
                RestClient.builder().baseUrl(BASE_URL).requestFactory(requestFactory()).build();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getModel() {
        return model;
    }

    @Override
    public boolean isEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public AiProviderResult generate(String prompt, boolean expectJson) {
        Map<String, Object> generationConfig = new LinkedHashMap<>();
        // expectJson=false(자유리포트 마크다운) 인 경우 API 레벨 JSON 강제를 걸지 않는다 —
        // 걸려 있으면 모델이 마크다운 대신 JSON 객체로 응답해 프론트 카드 UI가 깨진다.
        if (expectJson) generationConfig.put("responseMimeType", "application/json");
        generationConfig.put("temperature", 0.4);
        // 2.5 Flash 의 thinking 모드 — dynamic(-1)은 12~17초로 너무 느려서 제한.
        // 256: 4~6초 (살짝 떨어지지만 빠름), 512: 6~9초 (절충), -1: dynamic(원본).
        // ※ 모델별 유효 범위 주의 — flash-lite 는 512~24576 또는 0(off)만 허용 (256은 400 거부).
        generationConfig.put("thinkingConfig", Map.of("thinkingBudget", thinkingBudget));
        // 2026-07 실측: 종합(13종목) 리포트의 실제 내용이 3,890 토큰에서 MAX_TOKENS 로 잘렸다(마지막
        // 단기 추천 섹션 유실). 5120 이면 완결 여유가 있으면서도 폭주 출력이 요청을 수 분짜리로
        // 만드는 것은 막는다. 상향 시 read timeout 도 같이 늘려야 함 (생성 시간 ∝ 출력 토큰).
        generationConfig.put("maxOutputTokens", 5120);

        Map<String, Object> body =
                Map.of(
                        "systemInstruction",
                                Map.of(
                                        "parts",
                                        List.of(
                                                Map.of(
                                                        "text",
                                                        expectJson
                                                                ? JSON_SYSTEM_MESSAGE
                                                                : TEXT_SYSTEM_MESSAGE))),
                        "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                        "generationConfig", generationConfig);

        try {
            String response =
                    restClient
                            .post()
                            .uri("/v1beta/models/{model}:generateContent?key={key}", model, apiKey)
                            .header("Content-Type", "application/json")
                            .body(body)
                            .retrieve()
                            .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            JsonNode candidates = root.path("candidates");
            if (!candidates.isArray() || candidates.isEmpty()) {
                return AiProviderResult.error("응답에 candidate가 없습니다.");
            }
            JsonNode candidate = candidates.get(0);
            // 2.5 모델은 긴 응답을 여러 part 로 쪼개 반환할 수 있어 전부 이어붙인다 (thought part 제외).
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : candidate.path("content").path("parts")) {
                if (part.path("thought").asBoolean(false)) continue;
                sb.append(part.path("text").asText(""));
            }
            String text = sb.toString();
            if (text.isBlank()) return AiProviderResult.error("응답 텍스트가 비어있습니다.");
            logUsage(candidate, root.path("usageMetadata"));
            return AiProviderResult.success(text);

        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            String errSummary = formatErrorBody(e.getResponseBodyAsString());
            if (status == 429) {
                Instant retryAt = parseRetryAfter(e, Duration.ofMinutes(1));
                // 본문에 RESOURCE_EXHAUSTED / quota / billing 같은 reason 이 들어있어 진단에 결정적
                log.warn(
                        "[AI/{}] 429 rate limited, retryAt={}, reason={}",
                        name,
                        retryAt,
                        errSummary);
                return AiProviderResult.rateLimited(retryAt);
            }
            if (status >= 500 && status < 600) {
                // 503(UNAVAILABLE)·502 등 일시적 서버 과부하 — 60초 backoff 등록해서 잠시 다른 provider 우선
                // (Retry-After 헤더가 있으면 그 값 우선, 없으면 기본 60초)
                Instant retryAt = parseRetryAfter(e, Duration.ofSeconds(60));
                log.warn(
                        "[AI/{}] HTTP {} (일시 장애) — backoff until {}: {}",
                        name,
                        status,
                        retryAt,
                        errSummary);
                return AiProviderResult.rateLimited(retryAt);
            }
            log.warn("[AI/{}] HTTP {} — {}", name, status, errSummary);
            return AiProviderResult.error(name + " HTTP " + status);
        } catch (Exception e) {
            if (AiProvider.isReadTimeout(e)) {
                log.warn("[AI/{}] 응답 대기 {}초 초과 (read timeout)", name, readTimeout.toSeconds());
                return AiProviderResult.error("응답 대기 시간 초과");
            }
            log.warn("[AI/{}] 호출 실패: {}", name, e.getMessage());
            return AiProviderResult.error(e.getMessage());
        }
    }

    /** 응답 시간·잘림 진단용 — finishReason 이 MAX_TOKENS 면 리포트가 토큰 한도에서 끊긴 것. */
    private void logUsage(JsonNode candidate, JsonNode usage) {
        log.info(
                "[AI/{}] finishReason={}, promptTokens={}, outputTokens={}, thoughtsTokens={}",
                name,
                candidate.path("finishReason").asText("?"),
                usage.path("promptTokenCount").asInt(-1),
                usage.path("candidatesTokenCount").asInt(-1),
                usage.path("thoughtsTokenCount").asInt(-1));
    }

    private SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(readTimeout);
        return factory;
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
        } catch (Exception ignore) {
            /* fallthrough */
        }
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
