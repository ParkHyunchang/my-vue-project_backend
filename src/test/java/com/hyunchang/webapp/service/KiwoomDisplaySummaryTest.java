package com.hyunchang.webapp.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.service.kiwoom.KiwoomDisplaySummary;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class KiwoomDisplaySummaryTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void missingAmountsAreNotZeroAndCashIsNotOrderPower() throws Exception {
        var result =
                KiwoomDisplaySummary.domestic(
                        mapper.readTree("{\"entr\":\"1,000\"}"), mapper.readTree("{}"));
        assertEquals(new BigDecimal("1000"), result.get("deposit"));
        for (String key :
                new String[] {
                    "orderAvailable",
                    "totalEvaluation",
                    "totalAsset",
                    "profitLoss",
                    "stockProfitRate",
                    "d1Deposit"
                }) assertNull(result.get(key), key);
    }

    @Test
    void verifiedEmptyHoldingsAreZeroButAnIncompleteRowIsUnknown() throws Exception {
        var empty =
                KiwoomDisplaySummary.domestic(
                        mapper.readTree("{\"entr\":0}"),
                        mapper.readTree("{\"acnt_evlt_remn_indv_tot\":[]}"));
        assertEquals(BigDecimal.ZERO, empty.get("totalEvaluation"));
        var partial =
                KiwoomDisplaySummary.domestic(
                        mapper.readTree("{}"),
                        mapper.readTree("{\"acnt_evlt_remn_indv_tot\":[{\"rmnd_qty\":2}]}"));
        assertNull(partial.get("totalEvaluation"));
        assertNull(partial.get("profitLoss"));
    }
}
