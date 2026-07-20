package com.hyunchang.webapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class KrxOpenApiServiceTest {

    private KrxOpenApiService.KrSwingCandidate candidate(String symbol) {
        return new KrxOpenApiService.KrSwingCandidate(
                symbol, "삼성전자", "KOSPI", 70_000, 3.5, 1_000_000, 300_000, 3.3, LocalDate.now());
    }

    @Test
    void bareCodeStripsKospiSuffix() {
        assertEquals("005930", candidate("005930.KS").bareCode());
    }

    @Test
    void bareCodeStripsKosdaqSuffix() {
        assertEquals("196170", candidate("196170.KQ").bareCode());
    }

    @Test
    void bareCodeLeavesPlainCodeUnchanged() {
        assertEquals("005930", candidate("005930").bareCode());
    }
}
