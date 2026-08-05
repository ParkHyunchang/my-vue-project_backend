package com.hyunchang.webapp.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hyunchang.webapp.entity.KiwoomStrategyRun;
import com.hyunchang.webapp.entity.KiwoomTradeProposal;
import com.hyunchang.webapp.entity.SajuProfile;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 엔티티 직접 반환을 응답 DTO로 교체하면서 프론트가 읽던 JSON 모양이 바뀌지 않았는지 고정한다. 필드명이 하나만 어긋나도 JavaScript는 조용히 undefined가
 * 되므로 컴파일·기존 테스트로는 잡히지 않는다.
 */
class ResponseSerializationTest {

    private final ObjectMapper json =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(
                            com.fasterxml.jackson.databind.SerializationFeature
                                    .WRITE_DATES_AS_TIMESTAMPS);

    /**
     * paljaJson은 DB에 이미 JSON 문자열로 저장돼 있고, 엔티티가 @JsonRawValue로 escape 없이 내보내던 값이다. DTO로 옮기면서 이 동작이
     * 깨지면 프론트 PaljaDisplay가 객체 대신 문자열을 받아 사주팔자 표시가 통째로 깨진다.
     */
    @Test
    void sajuProfilePaljaStaysRawJsonObject() throws Exception {
        SajuProfile profile = new SajuProfile();
        profile.setId(7L);
        profile.setLabel("나");
        profile.setBirthDate(LocalDate.of(1988, 3, 14));
        profile.setBirthTime(LocalTime.of(5, 30));
        profile.setCalendarType("LUNAR");
        profile.setLunarYear(1988);
        profile.setLunarMonth(1);
        profile.setLunarDay(26);
        profile.setLeapMonth(true);
        profile.setPaljaJson("{\"yearPillar\":{\"stem\":\"무\",\"branch\":\"진\"}}");
        profile.setLastReportMarkdown("## 총평");

        JsonNode node = json.readTree(json.writeValueAsString(SajuProfileResponse.from(profile)));

        // 문자열이 아니라 객체로 나와야 한다 (@JsonRawValue 동작 확인)
        assertTrue(node.path("paljaJson").isObject(), "paljaJson이 raw JSON 객체가 아님");
        assertEquals("무", node.path("paljaJson").path("yearPillar").path("stem").asText());

        // 프론트(SavedProfilesPanel.vue)가 읽는 필드명 고정
        assertEquals(7, node.path("id").asInt());
        assertEquals("나", node.path("label").asText());
        assertEquals("1988-03-14", node.path("birthDate").asText());
        assertTrue(node.path("birthTime").asText().startsWith("05:30"));
        assertEquals("LUNAR", node.path("calendarType").asText());
        assertEquals(1988, node.path("lunarYear").asInt());
        assertEquals(1, node.path("lunarMonth").asInt());
        assertEquals(26, node.path("lunarDay").asInt());
        assertTrue(node.path("leapMonth").asBoolean());
        assertTrue(node.path("timeUnknown").isBoolean());
        assertEquals("## 총평", node.path("lastReportMarkdown").asText());
    }

    /** paljaJson이 비어 있어도 깨진 JSON을 만들지 않아야 한다. */
    @Test
    void sajuProfileWithoutPaljaSerializesAsNull() throws Exception {
        SajuProfile profile = new SajuProfile();
        profile.setLabel("아내");
        profile.setBirthDate(LocalDate.of(1990, 1, 1));

        JsonNode node = json.readTree(json.writeValueAsString(SajuProfileResponse.from(profile)));

        assertTrue(node.path("paljaJson").isNull());
    }

    /** 전략 이력 응답 — KiwoomStrategyPanel.vue가 읽는 필드명과 enum 문자열 표기를 고정한다. */
    @Test
    void strategyRunExposesFieldsUsedByThePanel() throws Exception {
        KiwoomStrategyRun run = new KiwoomStrategyRun();
        run.setStatus(KiwoomStrategyRun.Status.SUCCESS);
        run.setTriggeredBy(KiwoomStrategyRun.TriggeredBy.SCHEDULE);
        run.setMarketView("코스피 약보합");
        run.setAiCalled(true);
        run.setInputTokens(1200);
        run.setOutputTokens(340);

        KiwoomTradeProposal proposal = new KiwoomTradeProposal();
        proposal.setAction(KiwoomTradeProposal.Action.BUY);
        proposal.setStockCode("005930");
        proposal.setStockName("삼성전자");
        proposal.setQuantity(3);
        proposal.setLimitPrice(71_000L);
        proposal.setConfidence(88);
        proposal.setReason("거래량 급증");
        proposal.setGuardFlags("DAILY_LIMIT");

        JsonNode node =
                json.readTree(
                        json.writeValueAsString(
                                KiwoomStrategyRunResponse.from(
                                        run, List.of(KiwoomTradeProposalResponse.from(proposal)))));

        // enum은 이름 문자열이어야 한다 — 프론트가 RUN_STATUS_LABELS[status]로 매핑한다.
        assertEquals("SUCCESS", node.path("status").asText());
        assertEquals("SCHEDULE", node.path("triggeredBy").asText());
        assertEquals("코스피 약보합", node.path("marketView").asText());
        assertTrue(node.path("aiCalled").asBoolean());
        assertEquals(1200, node.path("inputTokens").asInt());
        assertEquals(340, node.path("outputTokens").asInt());

        JsonNode p = node.path("proposals").get(0);
        assertEquals("BUY", p.path("action").asText());
        assertEquals("005930", p.path("stockCode").asText());
        assertEquals("삼성전자", p.path("stockName").asText());
        assertEquals(3, p.path("quantity").asInt());
        assertEquals(71_000L, p.path("limitPrice").asLong());
        assertEquals(88, p.path("confidence").asInt());
        assertEquals("거래량 급증", p.path("reason").asText());
        assertEquals("DAILY_LIMIT", p.path("guardFlags").asText());
        assertEquals("PROPOSED", p.path("status").asText());

        // 브로커 원문 응답은 노출되지 않아야 한다.
        assertTrue(p.path("brokerResponse").isMissingNode());
    }
}
