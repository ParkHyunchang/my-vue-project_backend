package com.hyunchang.webapp.config;

import com.hyunchang.webapp.service.prompt.AiPromptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 앱 시작 시 AI 프롬프트 7종을 DB(ai_prompt_override)에 기본 지침으로 시드한다. 이미 행이 있으면 건너뛴다(관리자 수정 내용 보존). */
@Component
public class AiPromptInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AiPromptInitializer.class);

    private final AiPromptService aiPromptService;

    public AiPromptInitializer(AiPromptService aiPromptService) {
        this.aiPromptService = aiPromptService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            aiPromptService.seedDefaults();
        } catch (Exception e) {
            log.error("AI 프롬프트 기본 지침 시드 중 오류: {}", e.getMessage());
        }
    }
}
