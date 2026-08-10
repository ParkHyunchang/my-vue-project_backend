package com.hyunchang.webapp.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class KiwoomSellAvailabilityDiagnosticServiceTest {

    @Test
    void openSellOrderExplainsReducedSellableQuantity() {
        assertTrue(
                KiwoomSellAvailabilityDiagnosticService.isExplainedByUnfilledSellOrders(4, 2, 2));
    }

    @Test
    void warnsWhenOpenSellOrdersDoNotExplainAllUnavailableShares() {
        assertFalse(
                KiwoomSellAvailabilityDiagnosticService.isExplainedByUnfilledSellOrders(4, 0, 2));
    }
}
