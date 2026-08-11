package com.hyunchang.webapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.entity.KiwoomTradeProposal;
import com.hyunchang.webapp.repository.KiwoomTradeProposalRepository;
import com.hyunchang.webapp.service.kiwoom.KiwoomWebsocketClient;
import com.hyunchang.webapp.util.KiwoomMarketHours;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class KiwoomOrderSyncServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock private KiwoomTradeService trade;
    @Mock private KiwoomTradeProposalRepository proposals;
    @Mock private KiwoomStrategyAuditService audit;
    @Mock private KiwoomPositionExitService exits;
    @Mock private KiwoomWebsocketClient events;

    private KiwoomOrderSyncService service;
    private KiwoomTradeProposal proposal;

    @BeforeEach
    void setUp() {
        KiwoomProperties properties = new KiwoomProperties();
        properties.setAppKey("test-app-key");
        properties.setSecretKey("test-secret-key");
        properties.setAccountNo("12345678");
        service = new KiwoomOrderSyncService(trade, proposals, audit, properties, exits, events);

        proposal = new KiwoomTradeProposal();
        proposal.setAction(KiwoomTradeProposal.Action.SELL);
        proposal.setStockCode("063440");
        proposal.setStockName("SM Life Design");
        proposal.setQuantity(6);
        proposal.setReason("[EXIT:TAKE_PROFIT] 익절 지정가 주문");
        proposal.ordered("{}", "0357151");
        lenient()
                .when(proposals.findByStatusIn(any()))
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
    void takeProfitReregistrationRunsOutsideTheBrokerInquiryLock() throws Exception {
        JsonNode filled =
                objectMapper.readTree(
                        "{\"ord_no\":\"0357151\",\"cntr_qty\":\"6\",\"cntr_prc\":\"1235\"}");
        stubResponses(filled);
        List<String> nestedSync = new ArrayList<>();
        doAnswer(
                        invocation -> {
                            nestedSync.add(service.sync().message());
                            return null;
                        })
                .when(exits)
                .onOrderStateChanged();

        service.sync();

        // 락을 쥔 채 익절 주문을 다시 걸면 그 사이 손절 취소 확인과 장 시작 사전 복구가 튕긴다.
        assertEquals(List.of("동기화 대상 주문이 없습니다."), nestedSync);
    }

    @Test
    void disappearanceFromUnfilledOrdersDoesNotConfirmCancellation() {
        proposal.cancelRequested("테스트 취소", "{}");
        stubResponses(objectMapper.createArrayNode());

        service.sync();

        assertEquals(KiwoomTradeProposal.Status.CANCEL_REQUESTED, proposal.getStatus());
        verify(exits, never()).onOrderStateChanged();
    }

    @Test
    void cancelRequestStaysPendingWhileBrokerStillListsTheOrderAsUnfilled() throws Exception {
        proposal.cancelRequested("테스트 취소", "{}");
        JsonNode stillUnfilled =
                objectMapper.readTree(
                        "{\"ord_no\":\"0357151\",\"ord_qty\":\"6\",\"cntr_qty\":\"0\",\"oso_qty\":\"6\"}");
        stubOrderInquiries(stillUnfilled, objectMapper.createArrayNode());

        service.sync();

        // ORDERED로 되돌아가면 이미 접수된 취소를 다시 취소하려다 "취소가능수량이 없습니다"로 막힌다.
        assertEquals(KiwoomTradeProposal.Status.CANCEL_REQUESTED, proposal.getStatus());
        assertEquals(6, proposal.getRemainingQuantity());
    }

    @Test
    void repeatedDisappearanceAfterTheGracePeriodConfirmsCancellation() throws Exception {
        proposal.cancelRequested("테스트 취소", "{}");
        setCancelRequestedAt(LocalDateTime.now().minusSeconds(30));
        stubResponses(objectMapper.createArrayNode());

        service.sync();
        assertEquals(KiwoomTradeProposal.Status.CANCEL_REQUESTED, proposal.getStatus());

        service.sync();
        assertEquals(KiwoomTradeProposal.Status.CANCELED, proposal.getStatus());
        assertEquals(0, proposal.getRemainingQuantity());
        verify(exits).onOrderStateChanged();
    }

    @Test
    void aSingleMissedInquiryDoesNotConfirmCancellationAfterTheOrderReappears() throws Exception {
        proposal.cancelRequested("테스트 취소", "{}");
        setCancelRequestedAt(LocalDateTime.now().minusSeconds(30));
        JsonNode stillUnfilled =
                objectMapper.readTree(
                        "{\"ord_no\":\"0357151\",\"ord_qty\":\"6\",\"cntr_qty\":\"0\",\"oso_qty\":\"6\"}");

        stubResponses(objectMapper.createArrayNode());
        service.sync();
        stubOrderInquiries(stillUnfilled, objectMapper.createArrayNode());
        service.sync();
        stubResponses(objectMapper.createArrayNode());
        service.sync();

        assertEquals(KiwoomTradeProposal.Status.CANCEL_REQUESTED, proposal.getStatus());
    }

    @Test
    void explicitBrokerCancellationRecordConfirmsCancellation() throws Exception {
        proposal.cancelRequested("테스트 취소", "{}");
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
        proposal.cancelRequested("테스트 취소", "{}");
        when(exits.isStopTransitionPending("063440")).thenReturn(true);
        JsonNode cancelled =
                objectMapper.readTree(
                        "{\"ord_no\":\"0357999\",\"orig_ord_no\":\"0357151\","
                                + "\"ord_stt\":\"확인\",\"io_tp_nm\":\"-매도취소\","
                                + "\"ord_qty\":\"6\",\"cntr_qty\":\"0\",\"oso_qty\":\"0\"}");
        stubResponses(cancelled);

        try (MockedStatic<KiwoomMarketHours> marketHours = mockStatic(KiwoomMarketHours.class)) {
            marketHours.when(KiwoomMarketHours::isOpen).thenReturn(true);
            service.urgentStopCancellationSync();
        }

        assertEquals(KiwoomTradeProposal.Status.CANCELED, proposal.getStatus());
        verify(trade).getUnfilledOrders();
        verify(trade).getFilledOrders();
        verify(exits).onOrderStateChanged();
    }

    @Test
    void stalePartiallyFilledBuyCancelsOnlyItsRemainingQuantity() throws Exception {
        proposal.setAction(KiwoomTradeProposal.Action.BUY);
        proposal.setReason("AI 매수 주문");
        setOrderedAt(LocalDateTime.now().minusMinutes(16));
        JsonNode stillUnfilled =
                objectMapper.readTree(
                        "{\"ord_no\":\"0357151\",\"ord_qty\":\"6\",\"cntr_qty\":\"2\",\"oso_qty\":\"4\"}");
        stubOrderInquiries(stillUnfilled, objectMapper.createArrayNode());
        when(trade.cancelOrder(any())).thenReturn(Mono.just(objectMapper.createObjectNode()));

        service.sync();

        assertEquals(KiwoomTradeProposal.Status.CANCEL_REQUESTED, proposal.getStatus());
        assertEquals(2, proposal.getFilledQuantity());
        assertEquals(4, proposal.getRemainingQuantity());
        assertTrue(proposal.getCancelReason().contains("15분"));
        var requestCaptor =
                org.mockito.ArgumentCaptor.forClass(KiwoomTradeService.CancelOrderRequest.class);
        verify(trade).cancelOrder(requestCaptor.capture());
        assertEquals(4, requestCaptor.getValue().quantity());
        assertEquals("0357151", requestCaptor.getValue().originalOrderNo());
    }

    @Test
    void freshUnfilledBuyIsNotCancelled() throws Exception {
        proposal.setAction(KiwoomTradeProposal.Action.BUY);
        proposal.setReason("AI 매수 주문");
        setOrderedAt(LocalDateTime.now().minusMinutes(14));
        JsonNode stillUnfilled =
                objectMapper.readTree(
                        "{\"ord_no\":\"0357151\",\"ord_qty\":\"6\",\"cntr_qty\":\"0\",\"oso_qty\":\"6\"}");
        stubOrderInquiries(stillUnfilled, objectMapper.createArrayNode());

        service.sync();

        assertEquals(KiwoomTradeProposal.Status.ORDERED, proposal.getStatus());
        verify(trade, never()).cancelOrder(any());
    }

    @Test
    void ordinarySyncYieldsWhileStopTransitionIsPending() {
        when(exits.hasPendingStopTransitions()).thenReturn(true);

        try (MockedStatic<KiwoomMarketHours> marketHours = mockStatic(KiwoomMarketHours.class)) {
            marketHours.when(KiwoomMarketHours::isOpen).thenReturn(true);
            service.fastPendingBuySync();
            service.scheduledSync();
        }

        verify(trade, never()).getUnfilledOrders();
        verify(trade, never()).getFilledOrders();
    }

    @Test
    void longRunningSyncIsExposedAndAlertsOnlyOnce() throws Exception {
        Class<?> operationType =
                Class.forName(KiwoomOrderSyncService.class.getName() + "$SyncOperation");
        var constructor = operationType.getDeclaredConstructor(String.class, LocalDateTime.class);
        constructor.setAccessible(true);
        Object operation =
                constructor.newInstance("서버 재시작 주문 복구", LocalDateTime.now().minusSeconds(31));
        Field activeSync = KiwoomOrderSyncService.class.getDeclaredField("activeSync");
        activeSync.setAccessible(true);
        activeSync.set(service, operation);

        service.warnLongRunningSync();
        service.warnLongRunningSync();

        assertTrue(service.syncHealth().running());
        assertEquals("서버 재시작 주문 복구", service.syncHealth().operation());
        verify(audit).log(eq("ORDER_SYNC_DELAYED"), eq(null), contains("초 동안"));
        verify(events).publishEvent(eq("error"), contains("서버 재시작 주문 복구"));
    }

    @Test
    void ordinaryTakeProfitCancellationDoesNotUseUrgentPolling() {
        proposal.cancelRequested("테스트 취소", "{}");

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
        setField("orderedAt", value);
    }

    private void setCancelRequestedAt(LocalDateTime value) throws Exception {
        setField("cancelRequestedAt", value);
    }

    private void setField(String name, LocalDateTime value) throws Exception {
        Field field = KiwoomTradeProposal.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(proposal, value);
    }
}
