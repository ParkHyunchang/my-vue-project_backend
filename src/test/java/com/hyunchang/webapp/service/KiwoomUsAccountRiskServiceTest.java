package com.hyunchang.webapp.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.hyunchang.webapp.entity.*;
import com.hyunchang.webapp.repository.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KiwoomUsAccountRiskServiceTest {
    KiwoomUsAccountRiskRepository repository = mock(KiwoomUsAccountRiskRepository.class);
    KiwoomUsCapitalFlowRepository flows = mock(KiwoomUsCapitalFlowRepository.class);
    AtomicReference<KiwoomUsAccountRisk> saved = new AtomicReference<>();
    Map<String, KiwoomUsCapitalFlow> ledger = new HashMap<>();
    KiwoomUsAccountRiskService service = new KiwoomUsAccountRiskService(repository, flows);

    BigDecimal usd(String n) {
        return new BigDecimal(n);
    }

    @BeforeEach
    void setup() {
        when(repository.findById(1L)).thenAnswer(i -> Optional.ofNullable(saved.get()));
        when(repository.saveAndFlush(any()))
                .thenAnswer(
                        i -> {
                            saved.set(i.getArgument(0));
                            return saved.get();
                        });
        when(flows.findById(any()))
                .thenAnswer(i -> Optional.ofNullable(ledger.get(i.getArgument(0))));
    }

    @Test
    void disabledPolicyCannotBuyOrActivateWithoutBothConfirmations() {
        assertFalse(service.status().enabled());
        assertThrows(IllegalStateException.class, service::requireBuyAllowed);
        assertThrows(
                IllegalArgumentException.class, () -> service.activate(usd("1000"), false, true));
        service.activate(usd("1000"), true, true);
        service.requireBuyAllowed();
        assertThrows(IllegalStateException.class, () -> service.activate(usd("1200"), true, true));
    }

    @Test
    void peakTenPercentBoundaryLatchesAcrossServiceRestartAndRecovery() {
        service.activate(usd("1000"), true, true);
        service.observe(usd("1200"));
        assertFalse(service.observe(usd("1080.01")).triggered());
        assertTrue(service.observe(usd("1080")).triggered());
        var restarted = new KiwoomUsAccountRiskService(repository, flows);
        assertTrue(restarted.observe(usd("1300")).triggered());
        assertThrows(IllegalStateException.class, restarted::requireBuyAllowed);
    }

    @Test
    void depositDoesNotDilutePriorDrawdown() {
        service.activate(usd("1000"), true, true);
        service.requireFlowReview(true);
        var result = service.recordFlow("deposit-1", usd("1000"), true, usd("1900"));
        assertTrue(result.triggered());
        assertEquals(0, result.drawdownPercent().compareTo(usd("10")));
    }

    @Test
    void withdrawalDoesNotFabricateLossAndReplaysAreIdempotent() {
        service.activate(usd("1000"), true, true);
        service.requireFlowReview(true);
        var result = service.recordFlow("withdraw-1", usd("-200"), true, usd("800"));
        assertFalse(result.triggered());
        assertEquals(0, result.drawdownPercent().signum());
        ledger.put("withdraw-1", new KiwoomUsCapitalFlow("withdraw-1", usd("-200")));
        assertEquals(result, service.recordFlow("withdraw-1", usd("-200"), true, usd("800")));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.recordFlow("withdraw-1", usd("-201"), true, usd("799")));
    }

    @Test
    void reviewBlocksNewBuyAndSuspendsNewThresholdButPreservesExistingLatch() {
        service.activate(usd("1000"), true, true);
        service.requireFlowReview(true);
        assertFalse(service.observe(usd("100")).triggered());
        assertThrows(IllegalStateException.class, service::requireBuyAllowed);
        assertThrows(IllegalArgumentException.class, () -> service.requireFlowReview(false));
        assertTrue(service.recordFlow("no-flow", BigDecimal.ZERO, true, usd("100")).triggered());
    }

    @Test
    void flowRequiresReviewAndInvalidNavCannotMutateLedger() {
        service.activate(usd("1000"), true, true);
        assertThrows(
                IllegalStateException.class,
                () -> service.recordFlow("a", usd("1"), true, usd("1001")));
        service.requireFlowReview(true);
        assertThrows(
                IllegalStateException.class,
                () -> service.recordFlow("b", usd("1000"), true, usd("1000")));
        verify(flows, never()).saveAndFlush(any());
    }

    @Test
    void sendingStateStoresTimeBeforeBrokerCall() {
        var p = new KiwoomUsTradeProposal();
        p.setQuantity(10);
        p.submitting();
        assertEquals(KiwoomUsTradeProposal.Status.SUBMITTING, p.getStatus());
        assertNotNull(p.getOrderedAt());
        var time = p.getOrderedAt();
        p.unknown("restart");
        assertEquals(time, p.getOrderedAt());
    }
}
