package com.hyunchang.webapp.service;

import com.hyunchang.webapp.entity.KiwoomUsStrategySettings;
import com.hyunchang.webapp.service.KiwoomUsTradeService.DailyBar;
import com.hyunchang.webapp.service.KiwoomUsTradeService.OrderBookQuote;
import com.hyunchang.webapp.util.KiwoomUsMarketHours;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/** Completed, adjusted daily bars only; live Kiwoom bid confirms a 20-session breakout. */
@Service
public class KiwoomUsTechnicalSignalService {
    private final KiwoomUsTradeService trade;
    private final Map<String, CachedBars> cache = new ConcurrentHashMap<>();

    public KiwoomUsTechnicalSignalService(KiwoomUsTradeService trade) {
        this.trade = trade;
    }

    public Signal evaluate(
            String exchange,
            String symbol,
            OrderBookQuote quote,
            KiwoomUsStrategySettings settings) {
        LocalDate session = KiwoomUsMarketHours.today();
        try {
            // A benchmark outage must not trigger a separate stock request for every candidate.
            var spy = bars("NA", "SPY", session);
            var qqq = bars("ND", "QQQ", session);
            return calculate(bars(exchange, symbol, session), spy, qqq, quote, settings, session);
        } catch (RuntimeException error) {
            return Signal.unavailable("일봉 확인 실패: " + error.getClass().getSimpleName());
        }
    }

    private List<DailyBar> bars(String exchange, String symbol, LocalDate session) {
        String key = exchange + ":" + symbol;
        CachedBars cached = cache.get(key);
        if (cached != null && cached.session().equals(session)) return cached.bars();
        List<DailyBar> values = trade.getDailyBars(exchange, symbol).block(Duration.ofSeconds(15));
        validate(values, session);
        cache.put(key, new CachedBars(session, values));
        return values;
    }

    static Signal calculate(
            List<DailyBar> stock,
            List<DailyBar> spy,
            List<DailyBar> qqq,
            OrderBookQuote quote,
            KiwoomUsStrategySettings settings,
            LocalDate session) {
        validate(stock, session);
        validate(spy, session);
        validate(qqq, session);
        // Exact matching dates prevent comparing different return windows.
        for (int i = 1; i <= 51; i++) {
            LocalDate date = stock.get(stock.size() - i).date();
            if (!date.equals(spy.get(spy.size() - i).date())
                    || !date.equals(qqq.get(qqq.size() - i).date()))
                return Signal.unavailable("종목·지수의 거래일이 일치하지 않습니다.");
        }
        if (quote == null || quote.bid().signum() <= 0 || quote.ask().compareTo(quote.bid()) < 0)
            return Signal.unavailable("유효한 실시간 호가가 없습니다.");
        double stockReturn = return20(stock), spyReturn = return20(spy), qqqReturn = return20(qqq);
        double relative = stockReturn - Math.max(spyReturn, qqqReturn);
        double atr = atr14(stock);
        double breakout =
                stock.subList(stock.size() - 20, stock.size()).stream()
                        .mapToDouble(DailyBar::high)
                        .max()
                        .orElseThrow();
        double entry = quote.ask().doubleValue();
        if (!Double.isFinite(atr) || atr <= 0) return Signal.unavailable("ATR 변동성 데이터가 유효하지 않습니다.");
        double stopPercent = Math.max(0.5, atr * settings.getAtrStopMultiplier() / entry * 100);
        String reason;
        boolean accepted = false;
        if (!uptrend(spy) || !uptrend(qqq)) reason = "시장 추세 미충족: SPY·QQQ 종가/20일선/50일선 확인";
        else if (!uptrend(stock)) reason = "종목 추세 미충족";
        else if (relative < settings.getMinRelativeStrengthPercent())
            reason = "20거래일 지수 대비 상대강도 부족";
        else if (quote.bid().doubleValue() < breakout) reason = "20거래일 고가 돌파 대기";
        else if (entry > breakout + atr * settings.getMaxEntryExtensionAtr())
            reason = "돌파 후 과도한 가격 이격";
        else if (stopPercent > settings.getStopLossPercent()) reason = "ATR 손절폭이 설정된 최대 손실폭을 초과";
        else {
            accepted = true;
            reason = "시장·종목 상승추세, 지수 초과강도, 20거래일 고가 돌파";
        }
        return new Signal(
                true,
                accepted,
                reason,
                relative,
                stockReturn,
                spyReturn,
                qqqReturn,
                atr,
                breakout,
                stopPercent,
                stock.getLast().date());
    }

    private static void validate(List<DailyBar> bars, LocalDate session) {
        if (bars == null || bars.size() < 51) throw new IllegalStateException("일봉 51개 이상 필요");
        LocalDate previous = null;
        for (DailyBar bar : bars) {
            if (!bar.valid()
                    || !bar.date().isBefore(session)
                    || (previous != null && !bar.date().isAfter(previous)))
                throw new IllegalStateException("일봉 중복·역순·미완성 데이터");
            previous = bar.date();
        }
        LocalDate expected = session;
        for (int i = 1; i <= 51; i++) {
            expected = KiwoomUsMarketHours.previousTradingDay(expected);
            if (!bars.get(bars.size() - i).date().equals(expected))
                throw new IllegalStateException("최근 51거래일 일봉에 누락 또는 비거래일 데이터가 있음");
        }
    }

    private static boolean uptrend(List<DailyBar> bars) {
        return bars.getLast().close() > average(bars, 20) && average(bars, 20) > average(bars, 50);
    }

    private static double average(List<DailyBar> bars, int count) {
        return bars.subList(bars.size() - count, bars.size()).stream()
                .mapToDouble(DailyBar::close)
                .average()
                .orElseThrow();
    }

    private static double return20(List<DailyBar> bars) {
        return (bars.getLast().close() / bars.get(bars.size() - 21).close() - 1) * 100;
    }

    private static double atr14(List<DailyBar> bars) {
        double sum = 0;
        for (int i = bars.size() - 14; i < bars.size(); i++) {
            DailyBar bar = bars.get(i);
            double previous = bars.get(i - 1).close();
            sum +=
                    Math.max(
                            bar.high() - bar.low(),
                            Math.max(
                                    Math.abs(bar.high() - previous),
                                    Math.abs(bar.low() - previous)));
        }
        return sum / 14;
    }

    private record CachedBars(LocalDate session, List<DailyBar> bars) {}

    public record Signal(
            boolean available,
            boolean accepted,
            String reason,
            Double relativeStrengthPercent,
            Double stockReturn20,
            Double spyReturn20,
            Double qqqReturn20,
            Double atr,
            Double breakoutPrice,
            Double stopPercent,
            LocalDate dataDate) {
        static Signal unavailable(String reason) {
            return new Signal(false, false, reason, null, null, null, null, null, null, null, null);
        }
    }
}
