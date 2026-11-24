package com.hyunchang.webapp.service.strategybridge;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResearchStrategyEngineTest {
    @Test
    void allSixStrategiesMatchPythonHistoricalDecisionGoldenVectors() throws Exception {
        try (var stream =
                getClass().getResourceAsStream("/strategybridge/team24-decision-vectors.json")) {
            assertNotNull(stream);
            JsonNode vectors = new ObjectMapper().readTree(stream).path("vectors");
            assertEquals(144, vectors.size());
            for (var item : vectors) {
                Map<String, ResearchStrategyEngine.Signal> signals = new LinkedHashMap<>();
                for (String symbol : ResearchStrategyEngine.SYMBOLS) {
                    var s = item.path("snapshot").path(symbol);
                    signals.put(
                            symbol,
                            new ResearchStrategyEngine.Signal(
                                    s.path("eligible").asBoolean(),
                                    s.path("momentum").asDouble(),
                                    s.path("vol20").asDouble(),
                                    s.path("vol63").asDouble(),
                                    s.path("riskrank").asDouble()));
                }
                List<String> previous = new ArrayList<>();
                item.path("previousBasket").forEach(s -> previous.add(s.asText()));
                var actual =
                        ResearchStrategyEngine.decide(
                                item.path("candidateId").asText(),
                                previous,
                                signals,
                                item.path("monthly").asBoolean(),
                                item.path("equity").asDouble(),
                                item.path("peak").asDouble(),
                                item.path("hard").asBoolean(),
                                item.path("anchor").asDouble());
                var expected = item.path("expected");
                List<String> basket = new ArrayList<>();
                expected.path("basket").forEach(s -> basket.add(s.asText()));
                assertEquals(basket, actual.basket());
                assertEquals(expected.path("gross").asDouble(), actual.gross(), 1e-12);
                assertEquals(expected.path("mode").asText(), actual.mode());
                assertEquals(expected.path("weights").size(), actual.weights().size());
                actual.weights()
                        .forEach(
                                (s, w) ->
                                        assertEquals(
                                                expected.path("weights").path(s).asDouble(),
                                                w,
                                                1e-12));
            }
        }
    }

    @Test
    void malformedInputsAndUnknownStrategiesFailClosed() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ResearchStrategyEngine.riskTarget(100, 90, false, "recovery_vol", .2));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ResearchStrategyEngine.Signal(true, .1, Double.NaN, .1, 1));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ResearchStrategyEngine.decide(
                                "arbitrary:code",
                                List.of(),
                                Map.of(),
                                true,
                                100,
                                100,
                                false,
                                null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ResearchStrategyEngine.decide(
                                "rotation1:cushion80",
                                List.of(),
                                Map.of(),
                                true,
                                100,
                                100,
                                false,
                                null));
    }
}
