package com.hyunchang.webapp.service;

import com.hyunchang.webapp.entity.KiwoomStrategyRun;
import com.hyunchang.webapp.repository.KiwoomStrategyRunRepository;
import com.hyunchang.webapp.repository.KiwoomTradeProposalRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 판단 이력(run)·제안(proposal) 화면이 무한정 쌓이지 않도록 오래된 기록을 매일 새벽 정리한다. */
@Service
public class KiwoomStrategyHistoryCleanupService {
    private static final int RETENTION_DAYS = 3;

    private final KiwoomStrategyRunRepository runs;
    private final KiwoomTradeProposalRepository proposals;
    private final KiwoomStrategyAuditService audit;

    public KiwoomStrategyHistoryCleanupService(
            KiwoomStrategyRunRepository runs,
            KiwoomTradeProposalRepository proposals,
            KiwoomStrategyAuditService audit) {
        this.runs = runs;
        this.proposals = proposals;
        this.audit = audit;
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Seoul")
    public void cleanup() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(RETENTION_DAYS);
        List<KiwoomStrategyRun> stale = runs.findByCreatedAtBefore(cutoff);
        if (stale.isEmpty()) return;
        List<Long> staleIds = stale.stream().map(KiwoomStrategyRun::getId).toList();
        long deletedProposals = deleteStale(staleIds);
        audit.log(
                "HISTORY_CLEANUP",
                null,
                "최근 "
                        + RETENTION_DAYS
                        + "일보다 오래된 판단 "
                        + staleIds.size()
                        + "건·제안 "
                        + deletedProposals
                        + "건을 정리했습니다.");
    }

    @Transactional
    long deleteStale(List<Long> staleRunIds) {
        // 자식(proposal)을 먼저 지워야 run_id 외래키 제약에 걸리지 않는다.
        long deletedProposals = proposals.deleteByRunIdIn(staleRunIds);
        runs.deleteAllById(staleRunIds);
        return deletedProposals;
    }
}
