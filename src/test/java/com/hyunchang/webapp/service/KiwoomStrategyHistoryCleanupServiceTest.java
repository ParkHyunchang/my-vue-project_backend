package com.hyunchang.webapp.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hyunchang.webapp.entity.KiwoomStrategyRun;
import com.hyunchang.webapp.repository.KiwoomStrategyRunRepository;
import com.hyunchang.webapp.repository.KiwoomTradeProposalRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class KiwoomStrategyHistoryCleanupServiceTest {

    @Mock private KiwoomStrategyRunRepository runs;
    @Mock private KiwoomTradeProposalRepository proposals;
    @Mock private KiwoomStrategyAuditService audit;

    private KiwoomStrategyHistoryCleanupService service;

    private KiwoomStrategyRun runWithId(long id) {
        KiwoomStrategyRun run = new KiwoomStrategyRun();
        ReflectionTestUtils.setField(run, "id", id);
        return run;
    }

    @Test
    void deletesProposalsBeforeRunsAndLogsAudit() {
        service = new KiwoomStrategyHistoryCleanupService(runs, proposals, audit);
        when(runs.findByCreatedAtBefore(any())).thenReturn(List.of(runWithId(1L), runWithId(2L)));
        when(proposals.deleteByRunIdIn(List.of(1L, 2L))).thenReturn(5L);

        service.cleanup();

        verify(proposals).deleteByRunIdIn(List.of(1L, 2L));
        verify(runs).deleteAllById(List.of(1L, 2L));
        verify(audit).log(eq("HISTORY_CLEANUP"), isNull(), anyString());
    }

    @Test
    void doesNothingWhenNoStaleRuns() {
        service = new KiwoomStrategyHistoryCleanupService(runs, proposals, audit);
        when(runs.findByCreatedAtBefore(any())).thenReturn(List.of());

        service.cleanup();

        verify(proposals, never()).deleteByRunIdIn(any());
        verify(runs, never()).deleteAllById(any());
    }
}
