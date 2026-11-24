package com.hyunchang.webapp.service.paper;

import com.hyunchang.webapp.dto.paper.UsPaperDtos;
import com.hyunchang.webapp.entity.paper.UsPaperSession;
import com.hyunchang.webapp.repository.paper.*;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Collections;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(name = "us-paper.enabled", havingValue = "true")
public class UsPaperService {
    private final UsPaperSessionRepository sessions;
    private final UsPaperFillRepository fills;
    private final UsPaperEquityRepository equities;
    private final UsPaperEventRepository events;
    private final UsPaperEngineService engine;
    private final UsPaperMarketFeed feed;
    private final UsPaperMarketService market;
    private final Clock clock;
    private final boolean schedulerEnabled;
    private final TransactionTemplate snapshotTransaction;

    public UsPaperService(
            UsPaperSessionRepository sessions,
            UsPaperFillRepository fills,
            UsPaperEquityRepository equities,
            UsPaperEventRepository events,
            UsPaperEngineService engine,
            UsPaperMarketFeed feed,
            UsPaperMarketService market,
            Clock clock,
            @Value("${us-paper.scheduler-enabled:false}") boolean schedulerEnabled,
            PlatformTransactionManager transactionManager) {
        this.sessions = sessions;
        this.fills = fills;
        this.equities = equities;
        this.events = events;
        this.engine = engine;
        this.feed = feed;
        this.market = market;
        this.clock = clock;
        this.schedulerEnabled = schedulerEnabled;
        snapshotTransaction = new TransactionTemplate(transactionManager);
        snapshotTransaction.setReadOnly(true);
        snapshotTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshotTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public UsPaperDtos.Status create(BigDecimal initial) {
        return get(engine.create(initial));
    }

    public UsPaperDtos.Status start(Long id) {
        engine.start(id);
        return poll(id);
    }

    public UsPaperDtos.Status stop(Long id) {
        engine.stop(id);
        return get(id);
    }

    public UsPaperDtos.Status poll(Long id) {
        UsPaperSession current =
                sessions.findById(id)
                        .orElseThrow(() -> new EntityNotFoundException("모의 세션을 찾을 수 없습니다."));
        if (!"RUNNING".equals(current.getState())
                && !(current.isHardLatched() && current.getQuantity() > 0)) return get(id);
        if (!market.isOpen(clock.instant())) {
            engine.tick(id, null);
            return get(id);
        }
        UsPaperMarketFeed.MarketSnapshot snapshot;
        try {
            snapshot = feed.fetch();
        } catch (Exception ex) {
            engine.dataFailure(id);
            return get(id);
        }
        engine.tick(id, snapshot);
        return get(id);
    }

    public void pollRunning() {
        for (var s : sessions.findByStateIn(List.of("RUNNING", "HARD"))) {
            try {
                poll(s.getId());
            } catch (Exception ex) {
                // No unconfirmed ledger state is reused; the next tick retries after transaction
                // rollback.
                try {
                    engine.dataFailure(s.getId());
                } catch (Exception ignored) {
                    /* DB unavailable: fail closed. */
                }
            }
        }
    }

    public List<UsPaperDtos.Status> list() {
        return snapshotTransaction.execute(
                tx -> sessions.findTop100ByOrderByIdDesc().stream().map(this::status).toList());
    }

    public UsPaperDtos.Status get(Long id) {
        // Explicit template also applies to create/start/stop/poll self-calls, after ledger commit.
        return snapshotTransaction.execute(
                tx ->
                        status(
                                sessions.findById(id)
                                        .orElseThrow(
                                                () ->
                                                        new EntityNotFoundException(
                                                                "모의 세션을 찾을 수 없습니다."))));
    }

    private UsPaperDtos.Status status(UsPaperSession s) {
        BigDecimal strategy = UsPaperEngineService.percent(s.getEquityUsd(), s.getInitialCashUsd());
        BigDecimal benchmark =
                UsPaperEngineService.percent(s.getLastPriceUsd(), s.getBenchmarkBase());
        var history =
                new java.util.ArrayList<>(
                        equities.findTop100BySessionIdOrderByIdDesc(s.getId()).stream()
                                .map(
                                        e ->
                                                new UsPaperDtos.Equity(
                                                        e.getAt(),
                                                        e.getEquityUsd(),
                                                        e.getStrategyReturnPercent(),
                                                        e.getBenchmarkReturnPercent()))
                                .toList());
        Collections.reverse(history);
        return new UsPaperDtos.Status(
                s.getId(),
                s.getState(),
                true,
                "SPY_SMA200_80",
                s.getInitialCashUsd(),
                s.getCashUsd(),
                s.getQuantity(),
                "SPY",
                s.getLastPriceUsd(),
                s.getEquityUsd(),
                s.getPeakEquityUsd(),
                UsPaperEngineService.drawdown(s),
                s.getMaxDrawdownPercent(),
                s.isHardLatched(),
                s.isHardLatched() && s.getQuantity() > 0,
                5,
                5,
                s.getCreatedAt(),
                s.getStartedAt(),
                s.getObservedAt(),
                s.getQuotedAt(),
                market.isFresh(s.getQuotedAt(), clock.instant()),
                market.isOpen(clock.instant()),
                s.getReason(),
                s.getMessage(),
                s.getSource(),
                "SPY 가격수익률 대용 · 배당 미포함 · 지연 가능",
                strategy,
                benchmark,
                benchmark == null ? null : strategy.subtract(benchmark),
                schedulerEnabled,
                fills.findTop100BySessionIdOrderByIdDesc(s.getId()).stream()
                        .map(
                                f ->
                                        new UsPaperDtos.Fill(
                                                f.getId(),
                                                f.getAt(),
                                                f.getSide(),
                                                f.getSymbol(),
                                                f.getQuantity(),
                                                f.getPriceUsd(),
                                                f.getFeeUsd(),
                                                f.getReason()))
                        .toList(),
                history,
                events.findTop100BySessionIdOrderByIdDesc(s.getId()).stream()
                        .map(e -> new UsPaperDtos.Event(e.getAt(), e.getType(), e.getMessage()))
                        .toList());
    }
}
