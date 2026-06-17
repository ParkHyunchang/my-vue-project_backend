package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.service.ai.AiProviderChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 여행 플래너 — 목적지·기간·동행·스타일·예산을 받아 일자별 추천 일정을 생성한다.
 * 부동산/주식 AI 시황과 동일하게 AiProviderChain(Gemini → Groq → Cloudflare)을 재사용한다.
 * AI 제공자가 모두 차단/실패하면 blocked=true 와 다음 가능 시각을 반환한다.
 */
@Service
public class TravelPlannerService {

    private static final Logger log = LoggerFactory.getLogger(TravelPlannerService.class);

    private static final Pattern JSON_FENCE = Pattern.compile("```(?:json)?\\s*(\\{[\\s\\S]*?\\})\\s*```");
    private static final Pattern FIRST_OBJECT = Pattern.compile("\\{[\\s\\S]*\\}");

    private final AiProviderChain aiProviderChain;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TravelPlannerService(AiProviderChain aiProviderChain) {
        this.aiProviderChain = aiProviderChain;
    }

    public Map<String, Object> plan(String destination, int days, String companions,
                                    String style, String budget,
                                    boolean includeFlight, boolean includeStay) {
        String prompt = buildPrompt(destination, days, companions, style, budget, includeFlight, includeStay);
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
    public Map<String, Object> refine(String destination, int days, Object currentPlan, String instruction) {
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
        String currentJson = toJson(currentPlan);
        return """
            당신은 한국인 여행자를 위한 여행 플래너입니다. 아래는 현재 여행 일정(JSON)입니다.
            사용자의 수정 요청을 반영해 일정을 다시 구성하세요. 단, 다음 원칙을 지키세요:
            - 요청과 직접 관련된 부분만 바꾸고, 나머지 일자·일정 구성은 최대한 그대로 유지하세요.
            - 사용자가 특정 장소·식당·호텔을 정했다고 하면 그 항목을 그대로 반영하세요.
            - 실제 존재하는 장소 위주로, 동선이 효율적이게 유지하세요. 허구의 장소는 쓰지 마세요.
            - 전체 일정을 빠짐없이 다시 출력하세요(바뀐 부분만이 아니라 전체).
            응답은 반드시 아래 스키마의 JSON 객체 하나로만 출력하세요. 코드블록·해설을 포함하지 마세요.

            ── 현재 일정 ──
            %s

            ── 사용자 수정 요청 ──
            %s

            ── 응답 스키마 ──
            {
              "title": "여행 제목",
              "summary": "한두 문장 요약",
              "days": [
                {
                  "day": 1,
                  "theme": "그날의 테마",
                  "items": [
                    { "time": "오전" | "점심" | "오후" | "저녁" | "밤", "type": "관광" | "식당" | "카페" | "호텔" | "이동", "place": "장소명", "desc": "한 줄 설명" }
                  ]
                }
              ],
              "tips": ["팁1", "팁2"],
              "estimatedBudget": "1인 예상 경비 요약"
            }
            """.formatted(currentJson, nullSafe(instruction));
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

    private String buildPrompt(String destination, int days, String companions, String style, String budget,
                               boolean includeFlight, boolean includeStay) {
        int nights = Math.max(0, days - 1);
        String companionsText = (companions == null || companions.isBlank()) ? "미지정" : companions;
        String styleText = (style == null || style.isBlank()) ? "미지정" : style;
        String budgetText = (budget == null || budget.isBlank()) ? "미지정" : budget;
        String budgetScope = "항공권 " + (includeFlight ? "포함" : "불포함")
            + ", 숙박 " + (includeStay ? "포함" : "불포함");

        return """
            당신은 한국인 여행자를 위한 여행 플래너입니다. 아래 조건에 맞춰 현실적이고 동선이 효율적인
            일자별 추천 일정을 짜세요. 실제 존재하는 장소·명소·음식 위주로 구체적으로 작성하고,
            이동 동선이 들쭉날쭉하지 않게 가까운 곳끼리 묶으세요. 과장이나 허구의 장소는 쓰지 마세요.
            응답은 반드시 아래 스키마의 JSON 객체 하나로만 출력하세요. 코드블록·해설을 포함하지 마세요.

            ── 여행 조건 ──
            목적지: %s
            기간: %d일 (%d박 %d일)
            동행: %s
            여행 스타일: %s
            예산: %s
            예산 포함 항목: %s

            ── 예상 경비(estimatedBudget) 작성 지침 ──
            위 '예산 포함 항목'을 반드시 반영하세요. 불포함으로 표시된 항목(항공권/숙박)은 예상 경비에서 제외하고,
            무엇이 포함/불포함인지 한 줄로 함께 명시하세요. (예: "1인 약 60~80만원, 항공권·숙박 별도")

            ── 응답 스키마 ──
            {
              "title": "여행 제목 (예: 오사카 3박4일 미식 여행)",
              "summary": "한두 문장 요약",
              "days": [
                {
                  "day": 1,
                  "theme": "그날의 테마 (예: 도착·도톤보리 야경)",
                  "items": [
                    { "time": "오전" | "점심" | "오후" | "저녁" | "밤", "type": "관광" | "식당" | "카페" | "호텔" | "이동", "place": "장소명", "desc": "한 줄 설명" }
                  ]
                }
              ],
              "tips": ["현지 팁1", "팁2", "팁3"],
              "estimatedBudget": "1인 예상 경비 요약 (포함/불포함 항목 명시)"
            }
            반드시 days 배열의 길이는 %d 여야 합니다.
            """.formatted(
                nullSafe(destination), days, nights, days,
                companionsText, styleText, budgetText, budgetScope, days);
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

    private String nullSafe(String s) { return s == null ? "" : s; }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
