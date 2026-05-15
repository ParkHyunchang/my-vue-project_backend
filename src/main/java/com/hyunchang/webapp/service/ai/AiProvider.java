package com.hyunchang.webapp.service.ai;

/**
 * 무료 AI Provider 추상화. 종목 분석용 체인의 각 단계.
 *
 * 구현체 (등록 순서대로 시도됨):
 *   1. GeminiProvider (필수)
 *   2. GroqProvider (선택)
 *   3. CloudflareProvider (선택)
 *
 * isEnabled() 가 false 면 chain 에서 자동 skip 된다.
 */
public interface AiProvider {

    /** Chain 로그·UI 표시용 식별자. 예: "Gemini Flash" */
    String getName();

    /** 실제 호출 모델명. 예: "gemini-2.0-flash" */
    String getModel();

    /** API 키 등 설정이 갖춰져 있는가. false 면 chain 에서 skip. */
    boolean isEnabled();

    /**
     * 프롬프트를 보내 텍스트 응답을 받는다.
     * 호출자(Chain)가 success/rateLimited/error 를 판단할 수 있도록 결과 객체로 반환한다.
     */
    AiProviderResult generate(String prompt);
}
