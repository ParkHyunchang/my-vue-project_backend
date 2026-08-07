package com.hyunchang.webapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.entity.KiwoomTradeProposal;
import com.hyunchang.webapp.repository.KiwoomTradeProposalRepository;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class KiwoomOrderSyncServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock private KiwoomTradeService trade;
    @Mock private KiwoomTradeProposalRepository proposals;
    @Mock private KiwoomStrategyAuditService audit;
    @Mock private KiwoomPositionExitService exits;

    private KiwoomOrderSyncService service;
    private KiwoomTradeProposal proposal;

    @BeforeEach
    void setUp() {
        KiwoomProperties properties = new KiwoomProperties();
        properties.setAppKey("test-app-key");
        properties.setSecretKey("test-secret-key");
        properties.setAccountNo("12345678");
        service = new KiwoomOrderSyncService(trade, proposals, audit, properties, exits);

        proposal = new KiwoomTradeProposal();
        proposal.setAction(KiwoomTradeProposal.Action.SELL);
        proposal.setStockCode("063440");
        proposal.setStockName("SM Life Design");
        proposal.setQuantity(6);
        proposal.setReason("[EXIT:TAKE_PROFIT] 익절 지정가 주문");
        proposal.ordered("{}", "0357151");
        when(proposals.findByStatusIn(any()))
                .thenAnswer(
                        invocation -> {
                            List<KiwoomTradeProposal.Status> statuses = invocation.getArgument(0);
                            return statuses.contains(proposal.getStatus())
                                    ? List.of(proposal)
                                    : List.of();
                        });
        lenient()
                .when(proposals.findByBrokerOrderNo(anyString()))
                .thenAnswer(
                        invocation ->
                                "0357151".equals(invocation.getArgument(0))
                                        ? Optional.of(proposal)
                                        : Optional.empty());
        lenient().when(proposals.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void omittedExecutionFieldsDoNotTurnAnOrderIntoFilled() throws Exception {
        JsonNode ambiguous = objectMapper.readTree("{\"ord_no\":\"0357151\",\"ord_qty\":\"6\"}");
        stubResponses(ambiguous);

        service.sync();

        assertEquals(KiwoomTradeProposal.Status.ORDERED, proposal.getStatus());
        assertEquals(0, proposal.getFilledQuantity());
        assertEquals(6, proposal.getRemainingQuantity());
        verify(exits, never()).onOrderStateChanged();
    }

    @Test
    void explicitExecutionQuantityCanConfirmFullFill() throws Exception {
        JsonNode filled =
                objectMapper.readTree(
                        "{\"ord_no\":\"0357151\",\"cntr_qty\":\"6\",\"cntr_prc\":\"1235\"}");
        stubResponses(filled);

        service.sync();

        assertEquals(KiwoomTradeProposal.Status.FILLED, proposal.getStatus());
        assertEquals(6, proposal.getFilledQuantity());
        assertEquals(0, proposal.getRemainingQuantity());
        assertEquals(1235L, proposal.getAverageFillPrice());
        verify(exits).onOrderStateChanged();
    }

    @Test
    void disappearanceFromUnfilledOrdersDoesNotConfirmCancellation() {
        proposal.cancelRequested("{}");
        stubResponses(objectMapper.createArrayNode());

        service.sync();

        assertEquals(KiwoomTradeProposal.Status.CANCEL_REQUESTED, proposal.getStatus());
        verify(exits, never()).onOrderStateChanged();
    }

    @Test
    void explicitBrokerCancellationRecordConfirmsCancellation() throws Exception {
        proposal.cancelRequested("{}");
        JsonNode cancelled =
                objectMapper.readTree(
                        "{\"ord_no\":\"0357999\",\"orig_ord_no\":\"0357151\","
                                + "\"ord_stt\":\"확인\",\"io_tp_nm\":\"-매도취소\","
                                + "\"ord_qty\":\"6\",\"cntr_qty\":\"0\",\"oso_qty\":\"0\"}");
        stubResponses(cancelled);

        service.sync();

        assertEquals(KiwoomTradeProposal.Status.CANCELED, proposal.getStatus());
        assertEquals(0, proposal.getRemainingQuantity());
        verify(exits).onOrderStateChanged();
    }

    @Test
    void stopTransitionPollsImmediatelyAndConfirmsTakeProfitCancellation() throws Exception {
        proposal.cancelRequested("{}");
        when(exits.isStopTransitionPending("063440")).thenReturn(true);
        JsonNode cancelled =
                objectMapper.readTree(
                        "{\"ord_no\":\"0357999\",\"orig_ord_no\":\"0357151\","
                                + "\"ord_stt\":\"확인\",\"io_tp_nm\":\"-매도취소\","
                                + "\"ord_qty\":\"6\",\"cntr_qty\":\"0\",\"oso_qty\":\"0\"}");
        stubResponses(cancelled);

        service.urgentStopCancellationSync();

        assertEquals(KiwoomTradeProposal.Status.CANCELED, proposal.getStatus());
        verify(trade).getUnfilledOrders();
        verify(trade).getFilledOrders();
        verify(exits).onOrderStateChanged();
    }

    @Test
    void ordinaryTakeProfitCancellationDoesNotUseUrgentPolling() {
        proposal.cancelRequested("{}");

        service.urgentStopCancellationSync();

        assertEquals(KiwoomTradeProposal.Status.CANCEL_REQUESTED, proposal.getStatus());
        verify(trade, never()).getUnfilledOrders();
        verify(trade, never()).getFilledOrders();
    }

    @Test
    void previousDayTakeProfitMissingFromBrokerIsExpiredBeforeMarketOpen() throws Exception {
        LocalDate today = LocalDate.of(2026, 8, 7);
        setOrderedAt(today.minusDays(1).atTime(14, 30));
        stubResponses(objectMapper.createArrayNode());

        KiwoomOrderSyncService.PreMarketRecoveryResult result =
                service.reconcilePreviousDayOrders(today);

        assertEquals(true, result.success());
        assertEquals(1, result.expired());
        assertEquals(KiwoomTradeProposal.Status.CANCELED, proposal.getStatus());
    }

    @Test
    void previousDayBuyOrderMissingFromBrokerIsAlsoExpiredBeforeMarketOpen() throws Exception {
        LocalDate today = LocalDate.of(2026, 8, 7);
        proposal.setAction(KiwoomTradeProposal.Action.BUY);
        proposal.setReason("AI 매수 주문");
        setOrderedAt(today.minusDays(1).atTime(14, 30));
        stubResponses(objectMapper.createArrayNode());

        KiwoomOrderSyncService.PreMarketRecoveryResult result =
                service.reconcilePreviousDayOrders(today);

        assertEquals(true, result.success());
        assertEquals(1, result.expired());
        assertEquals(KiwoomTradeProposal.Status.CANCELED, proposal.getStatus());
    }

    @Test
    void sameDayOrderIsNotExpiredOnlyBecauseBrokerInquiryMissedIt() throws Exception {
        LocalDate today = LocalDate.of(2026, 8, 7);
        setOrderedAt(today.atTime(9, 0));
        stubResponses(objectMapper.createArrayNode());

        KiwoomOrderSyncService.PreMarketRecoveryResult result =
                service.reconcilePreviousDayOrders(today);

        assertEquals(true, result.success());
        assertEquals(0, result.expired());
        assertEquals(KiwoomTradeProposal.Status.ORDERED, proposal.getStatus());
    }

    @Test
    void previousDayOrderStillReportedUnfilledByBrokerIsKept() throws Exception {
        LocalDate today = LocalDate.of(2026, 8, 7);
        setOrderedAt(today.minusDays(1).atTime(14, 30));
        JsonNode unfilled =
                objectMapper.readTree(
                        "{\"ord_no\":\"0357151\",\"ord_qty\":\"6\",\"oso_qty\":\"6\"}");
        stubOrderInquiries(unfilled, objectMapper.createArrayNode());

        KiwoomOrderSyncService.PreMarketRecoveryResult result =
                service.reconcilePreviousDayOrders(today);

        assertEquals(true, result.success());
        assertEquals(0, result.expired());
        assertEquals(KiwoomTradeProposal.Status.ORDERED, proposal.getStatus());
    }

    private void stubResponses(JsonNode filledResponse) {
        stubOrderInquiries(objectMapper.createArrayNode(), filledResponse);
    }

    private void stubOrderInquiries(JsonNode unfilledResponse, JsonNode filledResponse) {
        when(trade.getUnfilledOrders()).thenReturn(Mono.just(unfilledResponse));
        when(trade.getFilledOrders()).thenReturn(Mono.just(filledResponse));
    }

    private void setOrderedAt(LocalDateTime value) throws Exception {
        Field field = KiwoomTradeProposal.class.getDeclaredField("orderedAt");
        field.setAccessible(true);
        field.set(proposal, value);
    }
}
