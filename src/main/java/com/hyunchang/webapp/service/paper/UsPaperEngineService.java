package com.hyunchang.webapp.service.paper;

import com.hyunchang.webapp.entity.paper.*;
import com.hyunchang.webapp.repository.paper.*;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Virtual ledger only: the locked session, fills, marks and deduplication commit together. */
@Service
@ConditionalOnProperty(name = "us-paper.enabled", havingValue = "true")
public class UsPaperEngineService {
    private static final BigDecimal FEE = new BigDecimal("0.0005"),
            SLIP = new BigDecimal("0.0005"),
            HUNDRED = new BigDecimal("100");
    private final UsPaperSessionRepository sessions;
    private final UsPaperFillRepository fills;
    private final UsPaperEquityRepository equities;
    private final UsPaperEventRepository events;
    private final UsPaperMarketService market;
    private final Clock clock;

    public UsPaperEngineService(
            UsPaperSessionRepository sessions,
            UsPaperFillRepository fills,
            UsPaperEquityRepository equities,
            UsPaperEventRepository events,
            UsPaperMarketService market,
            Clock clock) {
        this.sessions = sessions;
        this.fills = fills;
        this.equities = equities;
        this.events = events;
        this.market = market;
        this.clock = clock;
    }

    @Transactional
    public Long create(BigDecimal initial) {
        if (initial == null
                || initial.compareTo(new BigDecimal("100")) < 0
                || initial.compareTo(new BigDecimal("100000000")) > 0
                || initial.scale() > 2)
            throw new IllegalArgumentException("초기 가상자금은 USD100~100,000,000, 소수점 둘째 자리까지 입력하세요.");
        var s = new UsPaperSession();
        s.setInitialCashUsd(initial);
        s.setCashUsd(initial);
        s.setEquityUsd(initial);
        s.setPeakEquityUsd(initial);
        s.setCreatedAt(clock.instant());
        sessions.saveAndFlush(s);
        event(s, "CREATED", "가상 자금으로 새 세션을 생성했습니다. 실제 주문은 없습니다.");
        return s.getId();
    }

    @Transactional
    public void start(Long id) {
        var s = locked(id);
        if (s.isHardLatched()) throw new IllegalStateException("10% 위험 차단된 세션은 재개하거나 초기화할 수 없습니다.");
        if ("RUNNING".equals(s.getState())) return;
        s.setState("RUNNING");
        if (s.getStartedAt() == null) s.setStartedAt(clock.instant());
        note(s, "WAIT_QUOTE", "모의 운용 시작. 정규장과 유효 시세를 기다립니다.");
        event(s, "START", s.getMessage());
    }

    @Transactional
    public void stop(Long id) {
        var s = locked(id);
        if (s.isHardLatched()) return;
        if ("STOPPED".equals(s.getState())) return;
        s.setState("STOPPED");
        note(s, "STOPPED", "모든 가상 체결을 중지했습니다. 잔고와 고점은 유지됩니다.");
        event(s, "STOP", s.getMessage());
    }

    @Transactional
    public void dataFailure(Long id) {
        var s = locked(id);
        if (!"RUNNING".equals(s.getState()) && !s.isHardLatched()) return;
        if (!"DATA_ERROR".equals(s.getReason()))
            event(s, "DATA_ERROR", "공개 시세 조회 실패. 잔고/고점을 유지하며 가상 체결을 보류합니다.");
        note(s, "DATA_ERROR", "공개 시세 조회 실패. 다음 조회까지 거래를 보류합니다.");
    }

    @Transactional
    public void tick(Long id, UsPaperMarketFeed.MarketSnapshot snapshot) {
        var s = locked(id);
        Instant now = clock.instant();
        if (!"RUNNING".equals(s.getState()) && !(s.isHardLatched() && s.getQuantity() > 0)) return;
        if (!market.isOpen(now)) {
            note(s, "MARKET_CLOSED", "미국 정규장 대기. 휴장·단축장과 미등록 달력에서는 체결하지 않습니다.");
            return;
        }
        if (snapshot == null
                || snapshot.price() == null
                || snapshot.price().signum() <= 0
                || snapshot.quotedAt() == null) {
            note(s, "INVALID_QUOTE", "가격 또는 시세 시각이 누락되어 체결을 보류합니다.");
            return;
        }
        s.setQuotedAt(snapshot.quotedAt());
        s.setSource(snapshot.source() == null ? "UNKNOWN" : snapshot.source());
        if (!market.isFresh(snapshot.quotedAt(), now)
                || !market.isOpen(snapshot.quotedAt())
                || !snapshot.quotedAt()
                        .atZone(UsPaperMarketService.ET)
                        .toLocalDate()
                        .equals(now.atZone(UsPaperMarketService.ET).toLocalDate())) {
            note(s, "STALE_QUOTE", "시세가 5분 이상 지연되었거나 정규장 시세가 아닙니다. 체결하지 않습니다.");
            return;
        }
        if (s.getLastProcessedQuote() != null
                && !snapshot.quotedAt().isAfter(s.getLastProcessedQuote())) {
            note(s, "DUPLICATE_QUOTE", "이미 처리한 시세입니다. 중복 체결하지 않습니다.");
            return;
        }
        BigDecimal price = money(snapshot.price());
        s.setLastPriceUsd(price);
        s.setObservedAt(now);
        s.setLastProcessedQuote(snapshot.quotedAt());
        if (s.getBenchmarkBase() == null) s.setBenchmarkBase(price);
        mark(s, price);
        if (s.isHardLatched()) {
            if (s.getQuantity() > 0) fill(s, "SELL", s.getQuantity(), price, "HARD_10_PERCENT");
            mark(s, price);
            note(s, "HARD_LATCHED", "고점 대비10% 위험 차단. 새 매수와 재개가 영구 차단되었습니다.");
        } else {
            List<UsPaperMarketFeed.DailyBar> bars = snapshot.completedDailyBars();
            LocalDate signalDate = validateBars(bars, market.previousSession(now));
            if (signalDate == null)
                note(s, "HISTORY_NOT_READY", "직전 거래일을 포함한 완료 일봉200개가 필요합니다. 위험 감시는 유지됩니다.");
            else if (signalDate.equals(s.getLastSignalDate()))
                note(s, "WAIT_NEXT_SIGNAL", "오늘의 완료 일봉 신호를 처리했습니다. 위험 감시는 계속됩니다.");
            else {
                BigDecimal average =
                        bars.subList(bars.size() - 200, bars.size()).stream()
                                .map(UsPaperMarketFeed.DailyBar::close)
                                .reduce(BigDecimal.ZERO, BigDecimal::add)
                                .divide(new BigDecimal("200"), 12, RoundingMode.HALF_EVEN);
                boolean trend = bars.getLast().close().compareTo(average) > 0;
                s.setLastSignalDate(signalDate);
                long desired =
                        trend
                                ? s.getEquityUsd()
                                        .multiply(new BigDecimal("0.80"))
                                        .divide(
                                                price.multiply(BigDecimal.ONE.add(SLIP))
                                                        .multiply(BigDecimal.ONE.add(FEE)),
                                                0,
                                                RoundingMode.DOWN)
                                        .longValueExact()
                                : 0;
                if (s.getQuantity() > desired)
                    fill(
                            s,
                            "SELL",
                            s.getQuantity() - desired,
                            price,
                            trend ? "DAILY_REBALANCE" : "SMA200_EXIT");
                if (s.getQuantity() < desired) {
                    BigDecimal roundedFill = money(price.multiply(BigDecimal.ONE.add(SLIP)));
                    long affordable =
                            s.getCashUsd()
                                    .multiply(new BigDecimal("0.99"))
                                    .divide(
                                            roundedFill.multiply(BigDecimal.ONE.add(FEE)),
                                            0,
                                            RoundingMode.DOWN)
                                    .longValueExact();
                    long buy = Math.min(desired - s.getQuantity(), affordable);
                    while (buy > 0
                            && roundedFill
                                            .multiply(BigDecimal.valueOf(buy))
                                            .add(
                                                    money(
                                                            roundedFill
                                                                    .multiply(
                                                                            BigDecimal.valueOf(buy))
                                                                    .multiply(FEE)))
                                            .compareTo(
                                                    s.getCashUsd().multiply(new BigDecimal("0.99")))
                                    > 0) buy--;
                    if (buy > 0) fill(s, "BUY", buy, price, "SMA200_ENTRY");
                }
                mark(s, price);
                if (s.isHardLatched()) {
                    if (s.getQuantity() > 0)
                        fill(s, "SELL", s.getQuantity(), price, "HARD_10_PERCENT");
                    mark(s, price);
                    note(s, "HARD_LATCHED", "비용 반영 후10% 위험 차단이 발동했습니다.");
                } else
                    note(
                            s,
                            trend ? "SIGNAL_EXECUTED" : "WAIT_TREND",
                            trend
                                    ? "전일 완료 신호를 현재 시세로 가상 체결·조정했습니다."
                                    : "SPY가200일 이동평균 아래입니다. 현금으로 대기합니다.");
            }
        }
        saveEquity(s, now);
    }

    private LocalDate validateBars(List<UsPaperMarketFeed.DailyBar> bars, LocalDate expected) {
        if (bars == null || bars.size() < 200) return null;
        LocalDate previous = null;
        for (var b : bars) {
            if (b == null
                    || b.date() == null
                    || b.close() == null
                    || b.close().signum() <= 0
                    || previous != null && !b.date().isAfter(previous)) return null;
            previous = b.date();
        }
        if (!expected.equals(previous)) return null;
        LocalDate required = expected;
        for (int i = bars.size() - 1; i >= bars.size() - 200; i--) {
            if (!bars.get(i).date().equals(required)) return null;
            if (i > bars.size() - 200) {
                try {
                    required = market.previousSession(required);
                } catch (IllegalStateException unsupported) {
                    return null;
                }
            }
        }
        return previous;
    }

    private UsPaperSession locked(Long id) {
        return sessions.lockById(id)
                .orElseThrow(() -> new EntityNotFoundException("모의 세션을 찾을 수 없습니다."));
    }

    private void mark(UsPaperSession s, BigDecimal price) {
        s.setEquityUsd(
                money(s.getCashUsd().add(price.multiply(BigDecimal.valueOf(s.getQuantity())))));
        if (s.getEquityUsd().compareTo(s.getPeakEquityUsd()) > 0)
            s.setPeakEquityUsd(s.getEquityUsd());
        BigDecimal dd = drawdown(s);
        if (dd.compareTo(s.getMaxDrawdownPercent()) > 0) s.setMaxDrawdownPercent(dd);
        if (!s.isHardLatched() && dd.compareTo(BigDecimal.TEN) >= 0) {
            s.setHardLatched(true);
            s.setState("HARD");
            event(s, "HARD", "전체 가상계좌 고점 대비10% 차단 발동. 갭/비용으로 실제 낙폭은 초과할 수 있습니다.");
        }
    }

    private void fill(UsPaperSession s, String side, long qty, BigDecimal quote, String reason) {
        if (qty <= 0) return;
        boolean buy = "BUY".equals(side);
        BigDecimal price =
                money(
                        quote.multiply(
                                buy ? BigDecimal.ONE.add(SLIP) : BigDecimal.ONE.subtract(SLIP)));
        BigDecimal gross = price.multiply(BigDecimal.valueOf(qty)),
                fee = money(gross.multiply(FEE));
        if (buy) {
            if (s.isHardLatched()
                    || gross.add(fee).compareTo(s.getCashUsd().multiply(new BigDecimal("0.99")))
                            > 0) throw new IllegalStateException("가상 주문 현금 한도 위반");
            s.setCashUsd(money(s.getCashUsd().subtract(gross).subtract(fee)));
            s.setQuantity(s.getQuantity() + qty);
        } else {
            if (qty > s.getQuantity()) throw new IllegalStateException("가상 보유수량 부족");
            s.setCashUsd(money(s.getCashUsd().add(gross).subtract(fee)));
            s.setQuantity(s.getQuantity() - qty);
        }
        var f = new UsPaperFill();
        f.setSessionId(s.getId());
        f.setAt(clock.instant());
        f.setSide(side);
        f.setQuantity(qty);
        f.setPriceUsd(price);
        f.setFeeUsd(fee);
        f.setReason(reason);
        fills.save(f);
        event(s, "FILL", side + " SPY " + qty + "주 가상 체결");
    }

    private void saveEquity(UsPaperSession s, Instant now) {
        var e = new UsPaperEquity();
        e.setSessionId(s.getId());
        e.setAt(now);
        e.setEquityUsd(s.getEquityUsd());
        e.setStrategyReturnPercent(percent(s.getEquityUsd(), s.getInitialCashUsd()));
        e.setBenchmarkReturnPercent(percent(s.getLastPriceUsd(), s.getBenchmarkBase()));
        equities.save(e);
    }

    private void event(UsPaperSession s, String type, String message) {
        var e = new UsPaperEvent();
        e.setSessionId(s.getId());
        e.setAt(clock.instant());
        e.setType(type);
        e.setMessage(message);
        events.save(e);
    }

    private void note(UsPaperSession s, String reason, String message) {
        s.setReason(reason);
        s.setMessage(message);
    }

    static BigDecimal money(BigDecimal v) {
        return v.setScale(8, RoundingMode.HALF_EVEN);
    }

    public static BigDecimal percent(BigDecimal value, BigDecimal base) {
        return value == null || base == null || base.signum() <= 0
                ? null
                : value.divide(base, 12, RoundingMode.HALF_EVEN)
                        .subtract(BigDecimal.ONE)
                        .multiply(HUNDRED);
    }

    public static BigDecimal drawdown(UsPaperSession s) {
        return BigDecimal.ZERO.max(
                s.getPeakEquityUsd()
                        .subtract(s.getEquityUsd())
                        .divide(s.getPeakEquityUsd(), 12, RoundingMode.HALF_EVEN)
                        .multiply(HUNDRED));
    }
}
