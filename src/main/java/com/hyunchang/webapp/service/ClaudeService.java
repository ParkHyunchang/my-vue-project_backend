package com.hyunchang.webapp.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.dto.ChatMessage;
import com.hyunchang.webapp.exception.ClaudeApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;

@Slf4j
@Service
public class ClaudeService {

    private final RestClient restClient;
    private final String apiKey;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ClaudeService(@Value("${anthropic.api-key}") String apiKey) {
        this.apiKey = apiKey;
        this.restClient = RestClient.builder()
                .baseUrl("https://api.anthropic.com")
                .build();
    }

    public String chat(List<ChatMessage> messages) {
        List<AnthropicMessage> anthropicMessages = messages.stream()
                .map(m -> new AnthropicMessage(m.getRole(), m.getContent()))
                .toList();

        AnthropicRequest request = new AnthropicRequest("claude-sonnet-4-6", 1024, anthropicMessages);

        try {
            AnthropicResponse response = restClient.post()
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
            log.warn("[Claude API 오류] status={}, type={}, message={}", e.getStatusCode().value(), errorType, message);
            throw new ClaudeApiException(message);
        } catch (ResourceAccessException e) {
            log.warn("[Claude API 오류] 네트워크 연결 실패: {}", e.getMessage());
            throw new ClaudeApiException("[오류] Claude 서버에 연결할 수 없습니다. 네트워크 상태를 확인해주세요.");
        }
    }

    private String parseErrorType(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode typeNode = root.path("error").path("type");
            return typeNode.isMissingNode() ? "unknown" : typeNode.asText();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String toKoreanMessage(String errorType, int statusCode) {
        return switch (errorType) {
            case "overloaded_error"      -> "[오류] Claude 서버가 현재 혼잡합니다. 잠시 후 다시 시도해주세요.";
            case "rate_limit_error"      -> "[오류] API 요청 한도를 초과했습니다. 잠시 후 다시 시도해주세요.";
            case "authentication_error"  -> "[오류] API 인증에 실패했습니다. 관리자에게 문의해주세요.";
            case "permission_error"      -> "[오류] API 사용 권한이 없습니다. 관리자에게 문의해주세요.";
            case "invalid_request_error" -> "[오류] 잘못된 요청입니다. 메시지를 확인해주세요.";
            case "not_found_error"       -> "[오류] 요청한 리소스를 찾을 수 없습니다.";
            case "api_error"             -> "[오류] Claude API 내부 오류가 발생했습니다. 잠시 후 다시 시도해주세요.";
            default -> "[오류] 알 수 없는 오류가 발생했습니다. (HTTP " + statusCode + ")";
        };
    }

    record AnthropicMessage(String role, String content) {}

    record AnthropicRequest(
            String model,
            @JsonProperty("max_tokens") int maxTokens,
            List<AnthropicMessage> messages
    ) {}

    record AnthropicResponse(List<ContentBlock> content) {}

    record ContentBlock(String type, String text) {}
}
