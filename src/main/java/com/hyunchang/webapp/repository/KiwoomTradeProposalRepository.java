package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.*;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KiwoomTradeProposalRepository extends JpaRepository<KiwoomTradeProposal, Long> {
    List<KiwoomTradeProposal> findByRunIdInOrderByIdAsc(Collection<Long> ids);

    List<KiwoomTradeProposal> findByStatusIn(Collection<KiwoomTradeProposal.Status> statuses);

    Optional<KiwoomTradeProposal> findByBrokerOrderNo(String brokerOrderNo);

    long countByActionInAndCreatedAtGreaterThanEqual(
            Collection<KiwoomTradeProposal.Action> actions, LocalDateTime at);

    long countByActionInAndStatusInAndCreatedAtGreaterThanEqual(
            Collection<KiwoomTradeProposal.Action> actions,
            Collection<KiwoomTradeProposal.Status> statuses,
            LocalDateTime at);

    boolean existsByStockCodeAndActionInAndCreatedAtGreaterThanEqual(
            String code, Collection<KiwoomTradeProposal.Action> actions, LocalDateTime at);

    boolean existsByStockCodeAndActionAndStatusIn(
            String code,
            KiwoomTradeProposal.Action action,
            Collection<KiwoomTradeProposal.Status> statuses);

    Optional<KiwoomTradeProposal> findFirstByStockCodeAndActionAndStatusOrderByCreatedAtDesc(
            String code, KiwoomTradeProposal.Action action, KiwoomTradeProposal.Status status);

    long deleteByRunIdIn(Collection<Long> runIds);
}
