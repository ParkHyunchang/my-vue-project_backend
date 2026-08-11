package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.KiwoomUsTradeProposal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KiwoomUsTradeProposalRepository
        extends JpaRepository<KiwoomUsTradeProposal, Long> {
    List<KiwoomUsTradeProposal> findTop50ByOrderByIdDesc();

    List<KiwoomUsTradeProposal> findByStatusIn(Collection<KiwoomUsTradeProposal.Status> statuses);

    Optional<KiwoomUsTradeProposal> findByBrokerOrderNo(String value);

    long countByActionAndStatusAndOrderedAtAfter(
            KiwoomUsTradeProposal.Action action,
            KiwoomUsTradeProposal.Status status,
            LocalDateTime after);

    boolean existsBySymbolAndActionAndOrderedAtAfter(
            String symbol, KiwoomUsTradeProposal.Action action, LocalDateTime after);

    boolean existsBySymbolAndActionAndStatusInAndOrderedAtAfter(
            String symbol,
            KiwoomUsTradeProposal.Action action,
            Collection<KiwoomUsTradeProposal.Status> statuses,
            LocalDateTime after);
}
