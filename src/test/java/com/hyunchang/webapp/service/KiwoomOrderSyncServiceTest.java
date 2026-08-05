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
        service = new KiwoomOrderSyncService(trade, proposals, audit, properties, exits);

        proposal = new KiwoomTradeProposal();
        proposal.setAction(KiwoomTradeProposal.Action.SELL);
        proposal.setStockCode("063440");
        proposal.setStockName("SM Life Design");
        proposal.setQuantity(6);
        proposal.setReason("[EXIT:TAKE_PROFIT] 익절 지정가 주문");
        proposal.ordered("{}", "0357151");
        when(proposals.findByStatusIn(any())).thenAnswer(
                invocation -> {
                    List<KiwoomTradeProposal.Status> statuses = invocation.getArgument(0);
                    return statuses.contains(KiwoomTradeProposal.Status.ORDERED)
                            ? List.of(proposal)
                            : List.of();
                });
        lenient().when(proposals.findByBrokerOrderNo(anyString())).thenReturn(Optional.of(proposal));
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
                objectMapper.readTree("{\"ord_no\":\"0357151\",\"cntr_qty\":\"6\",\"cntr_prc\":\"1235\"}");
        stubResponses(filled);

        service.sync();

        assertEquals(KiwoomTradeProposal.Status.FILLED, proposal.getStatus());
        assertEquals(6, proposal.getFilledQuantity());
        assertEquals(0, proposal.getRemainingQuantity());
        assertEquals(1235L, proposal.getAverageFillPrice());
        verify(exits).onOrderStateChanged();
    }

    private void stubResponses(JsonNode filledResponse) {
        when(trade.getUnfilledOrders()).thenReturn(Mono.just(objectMapper.createArrayNode()));
        when(trade.getFilledOrders()).thenReturn(Mono.just(filledResponse));
    }
}
