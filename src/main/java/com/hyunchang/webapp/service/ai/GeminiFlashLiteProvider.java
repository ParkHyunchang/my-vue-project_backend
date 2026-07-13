package com.hyunchang.webapp.service.ai;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Google Gemini Flash-Lite provider (2차). Gemini Flash 와 같은 키·무료 한도를 쓰지만 별도 용량 풀이라 flash 가 503(피크
 * 혼잡)일 때도 응답하는 경우가 많다. 전체 프롬프트를 그대로 처리할 수 있어 Groq 컴팩트 폴백보다 리포트 품질이 좋다.
 *
 * <p>flash-lite 는 thinkingBudget 유효 범위가 512~24576 또는 0(off) — 256을 보내면 400 INVALID_ARGUMENT. 폴백
 * 역할이라 속도 우선으로 0(thinking off) 고정. read timeout 은 짧게(15초 — maxOutputTokens 5120 을 lite 속도로 생성하면
 * ~10초) 잡아 혼잡 시 빠르게 Groq 로 넘긴다.
 */
@Component
public class GeminiFlashLiteProvider extends GeminiProvider {

    public GeminiFlashLiteProvider(@Value("${gemini.api-key:}") String apiKey) {
        super(apiKey, "gemini-2.5-flash-lite", "Gemini Flash Lite", 0, Duration.ofSeconds(15));
    }
}
