package com.hyunchang.webapp.service.ai;

/**
 * 무료 AI Provider 추상화. 종목 분석용 체인의 각 단계.
 *
 * <p>구현체 (등록 순서대로 시도됨): 1. GeminiProvider (필수) 2. GroqProvider (선택) 3. CloudflareProvider (선택)
 *
 * <p>isEnabled() 가 false 면 chain 에서 자동 skip 된다.
 */
public interface AiProvider {
    String JSON_SYSTEM_MESSAGE =
            "You must respond with valid JSON only. Return only a JSON object.";
    String TEXT_SYSTEM_MESSAGE =
            "You are a helpful analysis assistant. Follow the output format the prompt requests "
                    + "(e.g. plain markdown) exactly, and do not wrap your response in a JSON object.";

    /** Chain 로그·UI 표시용 식별자. 예: "Gemini Flash" */
    String getName();

    /** 실제 호출 모델명. 예: "gemini-2.0-flash" */
    String getModel();

    /** API 키 등 설정이 갖춰져 있는가. false 면 chain 에서 skip. */
    boolean isEnabled();

    /**
     * 프롬프트를 보내 텍스트 응답을 받는다. expectJson=true 면 API 레벨 JSON 강제 모드까지 사용(여행/부동산 등 구조화 응답 소비자).
     * expectJson=false 면 순수 텍스트/마크다운 응답을 요청한다(주식·포트폴리오 자유리포트). 호출자(Chain)가
     * success/rateLimited/error 를 판단할 수 있도록 결과 객체로 반환한다.
     */
    AiProviderResult generate(String prompt, boolean expectJson);

    /** 기본값(expectJson=true) — 기존 호출부 호환용. */
    default AiProviderResult generate(String prompt) {
        return generate(prompt, true);
    }
}
