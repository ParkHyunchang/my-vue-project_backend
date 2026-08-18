package com.hyunchang.webapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class KiwoomUsFundamentalServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void readsForwardPeAndConvertsRoeRatioToPercent() throws Exception {
        YahooFinanceService yahoo = mock(YahooFinanceService.class);
        when(yahoo.fetchFundamentals("AAPL"))
                .thenReturn(
                        mapper.readTree(
                                """
                                {
                                  "summaryDetail": {"forwardPE": {"raw": 28.5}},
                                  "defaultKeyStatistics": {},
                                  "financialData": {"returnOnEquity": {"raw": 0.31}}
                                }
                                """));

        var snapshot = new KiwoomUsFundamentalService(yahoo).find("aapl").orElseThrow();

        assertEquals(28.5, snapshot.effectivePe(), 0.000_001);
        assertEquals(31.0, snapshot.roePercent(), 0.000_001);
    }

    @Test
    void excludesFundamentalsWhenRequiredValuesAreMissing() throws Exception {
        YahooFinanceService yahoo = mock(YahooFinanceService.class);
        when(yahoo.fetchFundamentals("TEST"))
                .thenReturn(
                        mapper.readTree(
                                """
                                {
                                  "summaryDetail": {"trailingPE": {"raw": 20}},
                                  "financialData": {}
                                }
                                """));

        assertTrue(new KiwoomUsFundamentalService(yahoo).find("TEST").isEmpty());
    }
}
