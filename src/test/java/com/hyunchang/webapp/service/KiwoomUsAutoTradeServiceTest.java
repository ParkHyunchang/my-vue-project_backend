package com.hyunchang.webapp.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.entity.KiwoomUsStrategyRun;
import com.hyunchang.webapp.repository.KiwoomUsAccountHoldingRepository;
import com.hyunchang.webapp.repository.KiwoomUsStrategyRunRepository;
import com.hyunchang.webapp.repository.KiwoomUsTradeProposalRepository;
import com.hyunchang.webapp.service.kiwoom.KiwoomUsAutoTradeState;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

class KiwoomUsAutoTradeServiceTest {

    @Test
    void decisionResultIsAlwaysWrittenToTheAuditLog() {
        KiwoomUsStrategyRunRepository runs = mock(KiwoomUsStrategyRunRepository.class);
        KiwoomUsAuditService audit = mock(KiwoomUsAuditService.class);
        KiwoomUsAutoTradeService service = service(mock(KiwoomUsTradeService.class), runs, audit);
        KiwoomUsStrategyRun run = new KiwoomUsStrategyRun();
        run.setTriggeredBy("SCHEDULE");

        ReflectionTestUtils.invokeMethod(
                service, "finishRun", run, "NO_CANDIDATE", "후보가 없습니다.", 0, null);

        verify(runs).save(run);
        verify(audit)
                .log(
                        eq("DECISION_RESULT"),
                        isNull(),
                        contains("[SCHEDULE][NO_CANDIDATE] 후보가 없습니다. 후보=0개"));
    }

    @Test
    void accountSummaryReturnsAnUnavailableSnapshotDuringSettlementWindow() {
        KiwoomUsTradeService trade = mock(KiwoomUsTradeService.class);
        when(trade.getDepositDetail())
                .thenReturn(Mono.error(new IllegalStateException("572070: 수도결제중입니다")));
        KiwoomUsAutoTradeService service =
                service(
                        trade,
                        mock(KiwoomUsStrategyRunRepository.class),
                        mock(KiwoomUsAuditService.class));

        KiwoomUsAutoTradeService.AccountSnapshot snapshot = service.accountSummary();

        assertFalse(snapshot.fresh());
        assertNull(snapshot.capturedAt());
        assertTrue(snapshot.notice().contains("수도결제"));
    }

    private KiwoomUsAutoTradeService service(
            KiwoomUsTradeService trade,
            KiwoomUsStrategyRunRepository runs,
            KiwoomUsAuditService audit) {
        return new KiwoomUsAutoTradeService(
                new KiwoomProperties(),
                trade,
                mock(KiwoomUsStrategySettingsService.class),
                mock(KiwoomUsIndexUniverseService.class),
                mock(KiwoomUsAutoTradeState.class),
                mock(KiwoomUsAccountHoldingRepository.class),
                mock(KiwoomUsTradeProposalRepository.class),
                runs,
                audit,
                mock(KiwoomUsEventService.class));
    }
}
