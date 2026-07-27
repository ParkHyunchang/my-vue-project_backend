package com.hyunchang.webapp.service;

import com.hyunchang.webapp.dto.SajuAnalysisResponse;
import com.hyunchang.webapp.dto.SajuBirthInputDto;
import com.hyunchang.webapp.dto.SajuResultDto;
import com.hyunchang.webapp.service.ai.AiProviderChain;
import com.hyunchang.webapp.service.prompt.AiPromptCatalog;
import com.hyunchang.webapp.service.prompt.AiPromptService;
import com.hyunchang.webapp.util.Texts;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 사주 AI 해석 — {@link SajuPaljaService}가 계산한 사주팔자를 근거로 AiProviderChain(Gemini → Groq →
 * Cloudflare)이 마크다운 해석 리포트를 생성한다. 주식/부동산/여행과 동일하게 "서버가 실제 데이터를 계산 → AI는 해석만" 패턴을 따른다.
 */
@Service
public class SajuAnalysisService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final SajuPaljaService sajuPaljaService;
    private final AiProviderChain aiProviderChain;
    private final AiPromptService aiPromptService;

    public SajuAnalysisService(
            SajuPaljaService sajuPaljaService,
            AiProviderChain aiProviderChain,
            AiPromptService aiPromptService) {
        this.sajuPaljaService = sajuPaljaService;
        this.aiProviderChain = aiProviderChain;
        this.aiPromptService = aiPromptService;
    }

    public SajuAnalysisResponse analyze(String label, SajuBirthInputDto input) {
        Optional<SajuResultDto> paljaOpt = sajuPaljaService.compute(input);
        if (paljaOpt.isEmpty()) {
            return SajuAnalysisResponse.builder()
                    .found(false)
                    .message(
                            sajuPaljaService.isConfigured()
                                    ? "사주팔자를 계산하지 못했습니다. 생년월일(윤달 여부 포함)을 확인하거나 잠시 후 다시 시도하세요."
                                    : "사주 계산 API 키가 설정되지 않았습니다.")
                    .build();
        }
        SajuResultDto palja = paljaOpt.get();

        String prompt = buildPrompt(label, input, palja);
        AiProviderChain.ChainResult chain = aiProviderChain.analyze(prompt, false);

        if (!chain.success()) {
            return SajuAnalysisResponse.builder()
                    .found(true)
                    .palja(palja)
                    .blocked(true)
                    .retryAt(chain.retryAt())
                    .providersStatus(chain.providersStatus())
                    .build();
        }

        return SajuAnalysisResponse.builder()
                .found(true)
                .palja(palja)
                .blocked(false)
                .providerName(chain.providerName())
                .model(chain.model())
                .analyzedAt(Instant.now())
                .report(Texts.cleanAiReport(chain.text()))
                .providersStatus(chain.providersStatus())
                .build();
    }

    private String buildPrompt(String label, SajuBirthInputDto input, SajuResultDto palja) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("이름", (label == null || label.isBlank()) ? "이 사람" : label.trim());
        vars.put("생년월일시", birthDateTimeText(input, palja.getSolarBirthDate()));
        vars.put("년주", palja.getYearPillar().getLabel());
        vars.put("월주", palja.getMonthPillar().getLabel());
        vars.put("일주", palja.getDayPillar().getLabel());
        vars.put("시주", palja.getHourPillar() == null ? "모름" : palja.getHourPillar().getLabel());
        vars.put("오행분포", fiveElementText(palja.getFiveElementCounts()));
        return aiPromptService.render(AiPromptCatalog.SAJU_ANALYSIS, vars);
    }

    private String birthDateTimeText(SajuBirthInputDto input, LocalDate solarBirthDate) {
        String timePart =
                (input.timeUnknown() || input.birthTime() == null)
                        ? "(태어난 시간 모름)"
                        : input.birthTime().format(TIME_FMT);
        if (input.isLunar()) {
            String leapText = input.leapMonth() ? "윤" : "평";
            return String.format(
                    "음력 %d-%02d-%02d(%s) → 양력 %s %s",
                    input.lunarYear(),
                    input.lunarMonth(),
                    input.lunarDay(),
                    leapText,
                    solarBirthDate.format(DATE_FMT),
                    timePart);
        }
        return solarBirthDate.format(DATE_FMT) + " " + timePart;
    }

    private String fiveElementText(Map<String, Integer> counts) {
        if (counts == null || counts.isEmpty()) return "정보 없음";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey()).append(" ").append(e.getValue()).append("개");
        }
        return sb.toString();
    }
}
