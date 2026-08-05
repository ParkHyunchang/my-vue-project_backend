package com.hyunchang.webapp.service;

import com.hyunchang.webapp.dto.KiwoomStrategyRunResponse;
import com.hyunchang.webapp.dto.KiwoomTradeProposalResponse;
import com.hyunchang.webapp.entity.KiwoomStrategyRun;
import com.hyunchang.webapp.entity.KiwoomTradeProposal;
import com.hyunchang.webapp.repository.KiwoomStrategyRunRepository;
import com.hyunchang.webapp.repository.KiwoomTradeProposalRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 전략 판단 이력 읽기 전용 조회 — 최근 run 목록과 각 run에 딸린 제안을 묶어 응답 DTO로 변환한다. 보관 기간이 지난 이력을 지우는 쪽은
 * KiwoomStrategyHistoryCleanupService가 담당한다.
 */
@Service
@Transactional(readOnly = true)
public class KiwoomStrategyHistoryService {

    /** 한 번에 내려주는 run 최대 개수 — 화면이 임의로 큰 limit을 보내도 여기서 잘린다. */
    private static final int MAX_LIMIT = 50;

    private final KiwoomStrategyRunRepository runs;
    private final KiwoomTradeProposalRepository proposals;

    public KiwoomStrategyHistoryService(
            KiwoomStrategyRunRepository runs, KiwoomTradeProposalRepository proposals) {
        this.runs = runs;
        this.proposals = proposals;
    }

    /** 최신순 run 목록. 제안은 run별로 묶어 한 번의 조회로 채운다(run마다 조회하면 N+1). */
    public List<KiwoomStrategyRunResponse> recentRuns(int limit) {
        List<KiwoomStrategyRun> list =
                runs.findByOrderByIdDesc(PageRequest.of(0, normalizeLimit(limit))).getContent();
        if (list.isEmpty()) return List.of();

        Map<Long, List<KiwoomTradeProposalResponse>> grouped = groupProposals(list);
        return list.stream()
                .map(
                        run ->
                                KiwoomStrategyRunResponse.from(
                                        run, grouped.getOrDefault(run.getId(), List.of())))
                .toList();
    }

    public long runCount() {
        return runs.count();
    }

    public long proposalCount() {
        return proposals.count();
    }

    private Map<Long, List<KiwoomTradeProposalResponse>> groupProposals(
            List<KiwoomStrategyRun> list) {
        List<Long> runIds = list.stream().map(KiwoomStrategyRun::getId).toList();
        Map<Long, List<KiwoomTradeProposalResponse>> grouped = new HashMap<>();
        for (KiwoomTradeProposal proposal : proposals.findByRunIdInOrderByIdAsc(runIds)) {
            grouped.computeIfAbsent(proposal.getRun().getId(), key -> new ArrayList<>())
                    .add(KiwoomTradeProposalResponse.from(proposal));
        }
        return grouped;
    }

    private int normalizeLimit(int limit) {
        return Math.min(Math.max(limit, 1), MAX_LIMIT);
    }
}
