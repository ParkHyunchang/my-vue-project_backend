package com.hyunchang.webapp.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.dto.KiwoomUsRiskResponse;
import com.hyunchang.webapp.entity.KiwoomUsAccountHolding;
import com.hyunchang.webapp.entity.KiwoomUsStrategySettings;
import com.hyunchang.webapp.entity.KiwoomUsTradeProposal;
import com.hyunchang.webapp.repository.KiwoomUsAccountHoldingRepository;
import com.hyunchang.webapp.repository.KiwoomUsStrategyRunRepository;
import com.hyunchang.webapp.repository.KiwoomUsTradeProposalRepository;
import com.hyunchang.webapp.service.kiwoom.KiwoomUsAutoTradeState;
import com.hyunchang.webapp.service.strategybridge.ResearchStrategyEngine;
import com.hyunchang.webapp.service.strategybridge.UsResearchDeploymentGateway;
import com.hyunchang.webapp.util.KiwoomUsMarketHours;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

class UsResearchExecutionIntegrationTest {
    private static final LocalDate DAY = LocalDate.parse("2026-11-23");
    private static final BigDecimal THOUSAND = new BigDecimal("1000");
    private final KiwoomUsTradeService trade = mock(KiwoomUsTradeService.class);
    private final KiwoomUsAutoTradeState controls = mock(KiwoomUsAutoTradeState.class);
    private final KiwoomUsAccountRiskService risk = mock(KiwoomUsAccountRiskService.class);
    private final UsResearchDeploymentGateway gateway = mock(UsResearchDeploymentGateway.class);
    private final KiwoomUsTradeProposalRepository proposals =
            mock(KiwoomUsTradeProposalRepository.class);
    private final KiwoomUsAccountHoldingRepository holdings =
            mock(KiwoomUsAccountHoldingRepository.class);
    private final List<KiwoomUsTradeProposal> saved = new ArrayList<>();
    private final KiwoomUsAutoTradeService service;

    UsResearchExecutionIntegrationTest() throws Exception {
        var properties = new KiwoomProperties();
        properties.setAppKey("fixture");
        properties.setSecretKey("fixture");
        properties.setAccountNo("fixture");
        properties.getUs().setStrategyEnabled(true);
        var settings = mock(KiwoomUsStrategySettingsService.class);
        when(settings.current()).thenReturn(new KiwoomUsStrategySettings());
        service =
                spy(
                        new KiwoomUsAutoTradeService(
                                properties,
                                trade,
                                settings,
                                mock(KiwoomUsFundamentalService.class),
                                mock(KiwoomUsIndexUniverseService.class),
                                mock(KiwoomUsTechnicalSignalService.class),
                                controls,
                                holdings,
                                proposals,
                                mock(KiwoomUsStrategyRunRepository.class),
                                mock(KiwoomUsAuditService.class),
                                mock(KiwoomUsEventService.class),
                                risk));
        service.setResearchDeployment(gateway);
        when(controls.tryStartDecision()).thenReturn(true);
        when(controls.isAutoTrading()).thenReturn(true);
        when(gateway.enabled()).thenReturn(true);
        when(gateway.managed()).thenReturn(true);
        Map<String, ResearchStrategyEngine.Signal> signals = new LinkedHashMap<>();
        for (String s : ResearchStrategyEngine.SYMBOLS)
            signals.put(s, new ResearchStrategyEngine.Signal(true, .2, .2, .2, 1));
        when(gateway.executionContext())
                .thenReturn(
                        new UsResearchDeploymentGateway.ExecutionContext(
                                "deployment-1",
                                "balanced_trend:recovery_vol",
                                "version-1",
                                DAY.minusDays(3),
                                Instant.now(),
                                signals,
                                null,
                                "market-hash"));
        when(gateway.claim(anyString(), any(), anyString(), anyString())).thenReturn(true);
        when(risk.status())
                .thenReturn(
                        new KiwoomUsRiskResponse(
                                true,
                                THOUSAND,
                                THOUSAND,
                                BigDecimal.ZERO,
                                10,
                                false,
                                false,
                                true,
                                OffsetDateTime.now(),
                                "fixture"));
        when(holdings.findByActiveTrueOrderByIdAsc()).thenReturn(List.of());
        when(proposals.findByStatusIn(any())).thenReturn(List.of());
        when(proposals.saveAndFlush(any()))
                .thenAnswer(
                        call -> {
                            KiwoomUsTradeProposal p = call.getArgument(0);
                            if (!saved.contains(p)) {
                                saved.add(p);
                                ReflectionTestUtils.setField(p, "id", (long) saved.size());
                            }
                            return p;
                        });
        when(proposals.save(any())).thenAnswer(call -> call.getArgument(0));
        var cash = new KiwoomUsTradeService.UsdCash(THOUSAND, "fixture", BigDecimal.ZERO, true, "");
        doReturn(
                        new KiwoomUsAutoTradeService.AccountSnapshot(
                                cash,
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                THOUSAND,
                                THOUSAND,
                                THOUSAND,
                                0,
                                0,
                                null,
                                true,
                                "",
                                LocalDateTime.now(),
                                null))
                .when(service)
                .refreshAccountSnapshot();
        doReturn(cash).when(service).refreshUsdCash();
        doNothing().when(service).syncHoldings();
        doNothing().when(service).reconcileOrders();
        when(trade.getOpenOrders())
                .thenReturn(Mono.just(new ObjectMapper().readTree("{\"result_list\":[]}")));
        when(trade.getOrderBook(anyString(), anyString()))
                .thenReturn(
                        Mono.just(
                                new KiwoomUsTradeService.OrderBookQuote(
                                        new BigDecimal("100"), new BigDecimal("100"))));
        when(trade.placeOrder(any()))
                .thenReturn(
                        Mono.just(new ObjectMapper().readTree("{\"ord_no\":\"fixture-order\"}")));
    }

    private MockedStatic<KiwoomUsMarketHours> hours() {
        var hours = mockStatic(KiwoomUsMarketHours.class, CALLS_REAL_METHODS);
        hours.when(KiwoomUsMarketHours::isEntryWindow).thenReturn(true);
        hours.when(KiwoomUsMarketHours::isRegularSession).thenReturn(true);
        hours.when(KiwoomUsMarketHours::today).thenReturn(DAY);
        return hours;
    }

    @Test
    void reviewedStrategyReachesRealOrderBoundaryAfterDurableClaim() {
        try (var ignored = hours()) {
            assertEquals("ORDERED", service.decide("TEST", true).status());
            assertFalse(saved.isEmpty());
            var order = inOrder(gateway, trade);
            order.verify(gateway)
                    .claim(
                            eq("deployment-1"),
                            eq(DAY.minusDays(3)),
                            eq("market-hash"),
                            anyString());
            order.verify(trade, atLeastOnce()).placeOrder(any());
            verify(gateway)
                    .finish(eq("deployment-1"), eq(DAY.minusDays(3)), eq("COMPLETED"), anyString());
            verify(trade, never()).getTradeValueTop();
            verify(risk, never()).activate(any(), anyBoolean(), anyBoolean());
            assertTrue(saved.stream().allMatch(p -> p.getReason().contains("version-1")));
            assertTrue(saved.stream().allMatch(p -> p.getExchange().length() == 2));
        }
    }

    @Test
    void inactiveOrDuplicateApprovalNeverFallsBackToLegacyOrders() {
        try (var ignored = hours()) {
            when(gateway.enabled()).thenReturn(false);
            assertEquals("RESEARCH_DISABLED", service.decide("TEST", true).status());
            when(gateway.enabled()).thenReturn(true);
            when(gateway.claim(anyString(), any(), anyString(), anyString())).thenReturn(false);
            assertEquals("ALREADY_PROCESSED", service.decide("TEST", true).status());
            verify(trade, never()).placeOrder(any());
            verify(trade, never()).getTradeValueTop();
        }
    }

    @Test
    void previewAndTradingOffNeverClaimOrSubmit() {
        try (var ignored = hours()) {
            assertEquals("PREVIEW", service.decide("TEST", false).status());
            when(controls.isAutoTrading()).thenReturn(false);
            assertEquals("PAUSED", service.decide("TEST", true).status());
            verify(gateway, never()).claim(anyString(), any(), anyString(), anyString());
            verify(trade, never()).placeOrder(any());
        }
    }

    @Test
    void oldManualHoldingIsIncludedInSellOrders() {
        var old = new KiwoomUsAccountHolding();
        old.setExchange("ND");
        old.setSymbol("OLD");
        old.sync("Old manual position", 2, 2, new BigDecimal("90"), new BigDecimal("100"), 1);
        when(holdings.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(old));
        try (var ignored = hours()) {
            assertEquals("ORDERED", service.decide("TEST", true).status());
            assertEquals("OLD", saved.getFirst().getSymbol());
            assertEquals(KiwoomUsTradeProposal.Action.SELL, saved.getFirst().getAction());
            assertEquals(2, saved.getFirst().getQuantity());
        }
    }

    @Test
    void ambiguousBrokerResponseLatchesStopAndWritesFailedOutcome() throws Exception {
        when(trade.placeOrder(any())).thenReturn(Mono.just(new ObjectMapper().readTree("{}")));
        try (var ignored = hours()) {
            assertEquals("SKIPPED", service.decide("TEST", true).status());
            assertEquals(KiwoomUsTradeProposal.Status.UNKNOWN, saved.getFirst().getStatus());
            verify(controls, atLeastOnce()).emergencyStop(anyString());
            verify(gateway).finish(eq("deployment-1"), any(), eq("FAILED"), anyString());
            verify(trade, times(1)).placeOrder(any());
        }
    }

    @Test
    void hardRiskLatchStopsAllStrategyBuysBeforeClaim() {
        doThrow(new IllegalStateException("10% latch")).when(risk).requireBuyAllowed();
        try (var ignored = hours()) {
            assertEquals("SKIPPED", service.decide("TEST", true).status());
            verify(gateway, never()).claim(anyString(), any(), anyString(), anyString());
            verify(trade, never()).placeOrder(any());
        }
    }

    @Test
    void changedBrokerAccountCannotOverwriteHoldingsRiskOrLiquidate() {
        doThrow(new IllegalStateException("different approved account"))
                .when(gateway)
                .requireAccountScope();
        doCallRealMethod().when(service).refreshAccountSnapshot();
        doCallRealMethod().when(service).syncHoldings();
        doCallRealMethod().when(service).reconcileOrders();
        assertThrows(IllegalStateException.class, service::refreshAccountSnapshot);
        assertThrows(IllegalStateException.class, service::syncHoldings);
        assertThrows(IllegalStateException.class, service::reconcileOrders);
        assertThrows(IllegalStateException.class, () -> service.reviewCapitalFlow(true));
        assertThrows(
                IllegalStateException.class,
                () -> service.recordCapitalFlow("fixture", BigDecimal.ONE, true));
        assertThrows(
                IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "liquidateAccount"));
        try (var time = hours()) {
            time.when(KiwoomUsMarketHours::isOpen).thenReturn(true);
            service.scheduledAccountAndExitSync();
        }
        verify(trade, never()).getDepositDetail();
        verify(trade, never()).getBalance();
        verify(trade, never()).placeOrder(any());
        verify(risk, never()).observe(any());
        verify(risk, never()).requireFlowReview(anyBoolean());
        verify(risk, never()).recordFlow(anyString(), any(), anyBoolean(), any());
    }
}
