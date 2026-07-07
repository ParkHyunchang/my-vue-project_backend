package com.hyunchang.webapp.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.dto.ChatMessage;
import com.hyunchang.webapp.exception.ClaudeApiException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Slf4j
@Service
public class ClaudeService {

    private static final String ANTHROPIC_BASE_URL = "https://api.anthropic.com";
    private static final String MODEL = "claude-sonnet-4-6";
    private static final int MAX_TOKENS = 1024;

    private final RestClient restClient;
    private final HttpClient streamingClient;
    private final String apiKey;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ClaudeService(@Value("${anthropic.api-key}") String apiKey) {
        this.apiKey = apiKey;
        this.restClient = RestClient.builder().baseUrl(ANTHROPIC_BASE_URL).build();
        this.streamingClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public String chat(List<ChatMessage> messages) {
        List<AnthropicMessage> anthropicMessages =
                messages.stream()
                        .map(m -> new AnthropicMessage(m.getRole(), m.getContent()))
                        .toList();

        AnthropicRequest request = new AnthropicRequest(MODEL, MAX_TOKENS, anthropicMessages);

        try {
            AnthropicResponse response =
                    restClient
                            .post()
                            .uri("/v1/messages")
                            .header("x-api-key", apiKey)
                            .header("anthropic-version", "2023-06-01")
                            .header("Content-Type", "application/json")
                            .body(request)
                            .retrieve()
                            .body(AnthropicResponse.class);

            if (response != null && response.content() != null && !response.content().isEmpty()) {
                return response.content().get(0).text();
            }
            return "";

        } catch (HttpStatusCodeException e) {
            String errorType = parseErrorType(e.getResponseBodyAsString());
            String message = toKoreanMessage(errorType, e.getStatusCode().value());
            log.warn(
                    "[Claude API 오류] status={}, type={}, message={}",
                    e.getStatusCode().value(),
                    errorType,
                    message);
            throw new ClaudeApiException(message);
        } catch (ResourceAccessException e) {
            log.warn("[Claude API 오류] 네트워크 연결 실패: {}", e.getMessage());
            throw new ClaudeApiException("[오류] Claude 서버에 연결할 수 없습니다. 네트워크 상태를 확인해주세요.");
        }
    }

    // Anthropic streaming API 응답을 라인 단위로 파싱하여 text_delta만 콜백으로 전달.
    // 호출 스레드를 블로킹하므로 호출자(SseEmitter 비동기 태스크)가 별도 스레드에서 실행해야 한다.
    public void chatStream(List<ChatMessage> messages, Consumer<String> onDelta) {
        List<AnthropicMessage> anthropicMessages =
                messages.stream()
                        .map(m -> new AnthropicMessage(m.getRole(), m.getContent()))
                        .toList();

        Map<String, Object> body = new HashMap<>();
        body.put("model", MODEL);
        body.put("max_tokens", MAX_TOKENS);
        body.put("messages", anthropicMessages);
        body.put("stream", true);

        String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new ClaudeApiException("[오류] 요청 직렬화에 실패했습니다.");
        }

        HttpRequest httpRequest =
                HttpRequest.newBuilder()
                        .uri(URI.create(ANTHROPIC_BASE_URL + "/v1/messages"))
                        .header("x-api-key", apiKey)
                        .header("anthropic-version", "2023-06-01")
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .timeout(Duration.ofSeconds(120))
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                        .build();

        try {
            HttpResponse<java.io.InputStream> response =
                    streamingClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() >= 400) {
                String errorBody =
                        new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                String errorType = parseErrorType(errorBody);
                String message = toKoreanMessage(errorType, response.statusCode());
                log.warn("[Claude 스트리밍 오류] status={}, type={}", response.statusCode(), errorType);
                throw new ClaudeApiException(message);
            }

            try (BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if (data.isEmpty()) continue;

                    JsonNode node = objectMapper.readTree(data);
                    String type = node.path("type").asText();
                    if ("content_block_delta".equals(type)) {
                        JsonNode delta = node.path("delta");
                        if ("text_delta".equals(delta.path("type").asText())) {
                            String text = delta.path("text").asText();
                            if (!text.isEmpty()) {
                                onDelta.accept(text);
                            }
                        }
                    }
                }
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("[Claude 스트리밍 오류] 네트워크 연결 실패: {}", e.getMessage());
            throw new ClaudeApiException("[오류] Claude 서버에 연결할 수 없습니다. 네트워크 상태를 확인해주세요.");
        }
    }

    private String parseErrorType(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode typeNode = root.path("error").path("type");
            return typeNode.isMissingNode() ? "unknown" : typeNode.asText();
        } catch (JsonProcessingException e) {
            return "unknown";
        }
    }

    private String toKoreanMessage(String errorType, int statusCode) {
        return switch (errorType) {
            case "overloaded_error" -> "[오류] Claude 서버가 현재 혼잡합니다. 잠시 후 다시 시도해주세요.";
            case "rate_limit_error" -> "[오류] API 요청 한도를 초과했습니다. 잠시 후 다시 시도해주세요.";
            case "authentication_error" -> "[오류] API 인증에 실패했습니다. 관리자에게 문의해주세요.";
            case "permission_error" -> "[오류] API 사용 권한이 없습니다. 관리자에게 문의해주세요.";
            case "invalid_request_error" -> "[오류] 잘못된 요청입니다. 메시지를 확인해주세요.";
            case "not_found_error" -> "[오류] 요청한 리소스를 찾을 수 없습니다.";
            case "api_error" -> "[오류] Claude API 내부 오류가 발생했습니다. 잠시 후 다시 시도해주세요.";
            default -> "[오류] 알 수 없는 오류가 발생했습니다. (HTTP " + statusCode + ")";
        };
    }

    record AnthropicMessage(String role, String content) {}

    record AnthropicRequest(
            String model,
            @JsonProperty("max_tokens") int maxTokens,
            List<AnthropicMessage> messages) {}

    record AnthropicResponse(List<ContentBlock> content) {}

    record ContentBlock(String type, String text) {}
}
