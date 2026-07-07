package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.service.ai.AiProviderChain;
import com.hyunchang.webapp.service.prompt.AiPromptCatalog;
import com.hyunchang.webapp.service.prompt.AiPromptService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * AI 여행 플래너 — 목적지·기간·동행·스타일·예산을 받아 일자별 추천 일정을 생성한다. 부동산/주식 AI 시황과 동일하게 AiProviderChain(Gemini →
 * Groq → Cloudflare)을 재사용한다. AI 제공자가 모두 차단/실패하면 blocked=true 와 다음 가능 시각을 반환한다.
 */
@Service
public class TravelPlannerService {

    private static final Logger log = LoggerFactory.getLogger(TravelPlannerService.class);

    private static final Pattern JSON_FENCE =
            Pattern.compile("```(?:json)?\\s*(\\{[\\s\\S]*?\\})\\s*```");
    private static final Pattern FIRST_OBJECT = Pattern.compile("\\{[\\s\\S]*\\}");

    private final AiProviderChain aiProviderChain;
    private final AiPromptService aiPromptService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TravelPlannerService(AiProviderChain aiProviderChain, AiPromptService aiPromptService) {
        this.aiProviderChain = aiProviderChain;
        this.aiPromptService = aiPromptService;
    }

    public Map<String, Object> plan(
            String destination,
            int days,
            String companions,
            String style,
            String budget,
            boolean includeFlight,
            boolean includeStay) {
        String prompt =
                buildPrompt(
                        destination, days, companions, style, budget, includeFlight, includeStay);
        AiProviderChain.ChainResult chain = aiProviderChain.analyze(prompt);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("destination", destination);
        res.put("days", days);
        res.put("providersStatus", chain.providersStatus());

        if (!chain.success()) {
            res.put("blocked", true);
            if (chain.retryAt() != null) res.put("retryAt", chain.retryAt().toString());
            return res;
        }

        res.put("blocked", false);
        res.put("providerName", chain.providerName());
        res.put("model", chain.model());
        res.put("analyzedAt", Instant.now().toString());
        res.put("plan", parsePlan(chain.text(), destination, days));
        return res;
    }

    /** 채팅 기반 일정 수정 — 현재 일정 + 사용자 요청을 받아 같은 스키마로 다시 출력. */
    public Map<String, Object> refine(
            String destination, int days, Object currentPlan, String instruction) {
        String prompt = buildRefinePrompt(currentPlan, instruction);
        AiProviderChain.ChainResult chain = aiProviderChain.analyze(prompt);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("destination", destination);
        res.put("days", days);
        res.put("providersStatus", chain.providersStatus());

        if (!chain.success()) {
            res.put("blocked", true);
            if (chain.retryAt() != null) res.put("retryAt", chain.retryAt().toString());
            return res;
        }

        res.put("blocked", false);
        res.put("providerName", chain.providerName());
        res.put("model", chain.model());
        res.put("analyzedAt", Instant.now().toString());
        res.put("plan", parsePlan(chain.text(), destination, days));
        return res;
    }

    private String buildRefinePrompt(Object currentPlan, String instruction) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("현재일정", toJson(currentPlan));
        vars.put("수정요청", nullSafe(instruction));
        return aiPromptService.render(AiPromptCatalog.TRAVEL_REFINE, vars);
    }

    private String toJson(Object o) {
        if (o == null) return "{}";
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            log.warn("[AI/Travel] 현재 일정 직렬화 실패: {}", e.getMessage());
            return "{}";
        }
    }

    private String buildPrompt(
            String destination,
            int days,
            String companions,
            String style,
            String budget,
            boolean includeFlight,
            boolean includeStay) {
        int nights = Math.max(0, days - 1);
        String companionsText = (companions == null || companions.isBlank()) ? "미지정" : companions;
        String styleText = (style == null || style.isBlank()) ? "미지정" : style;
        String budgetText = (budget == null || budget.isBlank()) ? "미지정" : budget;
        String budgetScope =
                "항공권 " + (includeFlight ? "포함" : "불포함") + ", 숙박 " + (includeStay ? "포함" : "불포함");

        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("목적지", nullSafe(destination));
        vars.put("기간일수", String.valueOf(days));
        vars.put("박수", String.valueOf(nights));
        vars.put("동행", companionsText);
        vars.put("여행스타일", styleText);
        vars.put("예산", budgetText);
        vars.put("예산포함항목", budgetScope);
        return aiPromptService.render(AiPromptCatalog.TRAVEL_CREATE, vars);
    }

    private Map<String, Object> parsePlan(String text, String destination, int days) {
        String json = extractJson(text);
        try {
            JsonNode root = objectMapper.readTree(json);
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("title", textOf(root, "title", destination + " " + days + "일 여행"));
            plan.put("summary", textOf(root, "summary", ""));
            plan.put("estimatedBudget", textOf(root, "estimatedBudget", ""));
            plan.put("tips", stringList(root.path("tips")));

            List<Map<String, Object>> dayList = new ArrayList<>();
            JsonNode daysNode = root.path("days");
            if (daysNode.isArray()) {
                int idx = 1;
                for (JsonNode d : daysNode) {
                    Map<String, Object> day = new LinkedHashMap<>();
                    day.put("day", d.path("day").isNumber() ? d.path("day").asInt() : idx);
                    day.put("theme", textOf(d, "theme", ""));
                    List<Map<String, Object>> items = new ArrayList<>();
                    JsonNode itemsNode = d.path("items");
                    if (itemsNode.isArray()) {
                        for (JsonNode it : itemsNode) {
                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("time", textOf(it, "time", ""));
                            item.put("type", textOf(it, "type", "관광"));
                            item.put("place", textOf(it, "place", ""));
                            item.put("desc", textOf(it, "desc", ""));
                            items.add(item);
                        }
                    }
                    day.put("items", items);
                    dayList.add(day);
                    idx++;
                }
            }
            plan.put("days", dayList);
            return plan;
        } catch (Exception e) {
            log.warn("[AI/Travel] JSON 파싱 실패: {} (head: {})", e.getMessage(), truncate(text, 200));
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("title", destination + " " + days + "일 여행");
            fallback.put("summary", truncate(text, 500));
            fallback.put("estimatedBudget", "");
            fallback.put("tips", List.of());
            fallback.put("days", List.of());
            return fallback;
        }
    }

    private String extractJson(String text) {
        if (text == null) return "{}";
        String trimmed = text.trim();
        Matcher m = JSON_FENCE.matcher(trimmed);
        if (m.find()) return m.group(1);
        Matcher m2 = FIRST_OBJECT.matcher(trimmed);
        if (m2.find()) return m2.group();
        return trimmed;
    }

    private String textOf(JsonNode node, String field, String fallback) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? fallback : v.asText(fallback);
    }

    private List<String> stringList(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) arr.forEach(n -> out.add(n.asText()));
        return out;
    }

    private String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
