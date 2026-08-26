package com.hyunchang.webapp.service;

import com.hyunchang.webapp.entity.KiwoomStrategySettings;
import com.hyunchang.webapp.util.KiwoomMarketHours;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/** 국내 신규 매수 후보의 유동성·추세·변동성·호가를 코드로 강제 검증한다. */
@Service
public class KiwoomCandidateQualityService {
    private static final long TECHNICAL_CACHE_MS = Duration.ofMinutes(10).toMillis();
    private final KiwoomTradeService trade;
    private final Map<String, CachedTechnical> technicalCache = new ConcurrentHashMap<>();

    public KiwoomCandidateQualityService(KiwoomTradeService trade) {
        this.trade = trade;
    }

    public CandidateQuality evaluate(
            KrxOpenApiService.KrSwingCandidate candidate, KiwoomStrategySettings settings) {
        if (candidate.marketCap() <= 0)
            return CandidateQuality.missing(candidate.bareCode(), "시가총액");
        if (candidate.tradingValue() <= 0)
            return CandidateQuality.missing(candidate.bareCode(), "당일 누적 거래대금");
        if (candidate.marketCap() < settings.getMinMarketCapWon())
            return CandidateQuality.rejected(
                    candidate.bareCode(),
                    "시가총액",
                    String.format(
                            "%,d원 < %,d원", candidate.marketCap(), settings.getMinMarketCapWon()));
        long requiredTradingValue =
                intradayTradingValueThreshold(
                        settings.getMinTradingValueWon(), LocalTime.now(KiwoomMarketHours.KST));
        if (candidate.tradingValue() < requiredTradingValue)
            return CandidateQuality.rejected(
                    candidate.bareCode(),
                    "거래대금",
                    String.format(
                            "%,d원 < 장중 시간보정 %,d원 (종가 기준 %,d원)",
                            candidate.tradingValue(),
                            requiredTradingValue,
                            settings.getMinTradingValueWon()));
        try {
            Technical technical = technical(candidate.bareCode());
            if (!technical.complete())
                return CandidateQuality.missing(
                        candidate.bareCode(),
                        "수정주가 일봉 부족/무효: 유효="
                                + technical.candleCount()
                                + "개, 필요=21개, MA5="
                                + format(technical.ma5())
                                + ", MA20="
                                + format(technical.ma20())
                                + ", ATR="
                                + format(technical.atrPercent())
                                + "%");
            String sectorCode = technical.sectorCode();
            if (sectorCode == null || sectorCode.isBlank()) {
                // ka10081은 종목에 따라 업종 필드를 보내지 않는다. 가격 데이터가 정상인데 업종만
                // 비었다는 이유로 후보를 제거하지 않고, 시장 단위의 보수적인 그룹으로 대체한다.
                sectorCode = "MARKET:" + candidate.market();
            }
            if (candidate.closePrice() <= technical.ma20())
                return CandidateQuality.rejected(candidate.bareCode(), "20일선", "현재가가 20일선 이하");
            if (technical.ma5() <= technical.ma20())
                return CandidateQuality.rejected(candidate.bareCode(), "이동평균 배열", "5일선이 20일선 이하");
            if (technical.ma20() < technical.previousMa20())
                return CandidateQuality.rejected(candidate.bareCode(), "20일선 기울기", "20일선 하락");
            double priceAboveMa20 =
                    (candidate.closePrice() - technical.ma20()) * 100.0 / technical.ma20();
            if (priceAboveMa20 > settings.getMaxPriceAboveMa20Percent())
                return CandidateQuality.rejected(
                        candidate.bareCode(),
                        "20일선 과열",
                        String.format(
                                "%.2f%% > %.2f%%",
                                priceAboveMa20, settings.getMaxPriceAboveMa20Percent()));
            if (technical.atrPercent() > settings.getMaxAtrPercent())
                return CandidateQuality.rejected(
                        candidate.bareCode(),
                        "ATR",
                        String.format(
                                "%.2f%% > %.2f%%",
                                technical.atrPercent(), settings.getMaxAtrPercent()));
            KiwoomTradeService.OrderBookQuote quote =
                    trade.getOrderBookQuote(candidate.bareCode()).block(Duration.ofSeconds(10));
            if (quote == null) return CandidateQuality.missing(candidate.bareCode(), "실시간 최우선 호가");
            if (quote.spreadPercent() > settings.getMaxSpreadPercent())
                return CandidateQuality.rejected(
                        candidate.bareCode(),
                        "호가 스프레드",
                        String.format(
                                "%.3f%% > %.3f%%",
                                quote.spreadPercent(), settings.getMaxSpreadPercent()));
            return CandidateQuality.accepted(
                    candidate.bareCode(),
                    sectorCode,
                    technical.ma5(),
                    technical.ma20(),
                    technical.atrPercent(),
                    priceAboveMa20,
                    quote.spreadPercent(),
                    quote.ask());
        } catch (Exception e) {
            return CandidateQuality.missing(
                    candidate.bareCode(), e.getMessage() == null ? "기술지표·호가" : e.getMessage());
        }
    }

    public SpreadCheck checkLiveSpread(String stockCode, double maxSpreadPercent) {
        try {
            KiwoomTradeService.OrderBookQuote quote =
                    trade.getOrderBookQuote(stockCode).block(Duration.ofSeconds(10));
            if (quote == null) return new SpreadCheck(false, 0, 0, 0, "최우선 호가 없음");
            boolean accepted = quote.spreadPercent() <= maxSpreadPercent;
            return new SpreadCheck(
                    accepted,
                    quote.bid(),
                    quote.ask(),
                    quote.spreadPercent(),
                    accepted ? "통과" : "허용 스프레드 초과");
        } catch (Exception e) {
            return new SpreadCheck(
                    false, 0, 0, 0, e.getMessage() == null ? "호가 조회 실패" : e.getMessage());
        }
    }

    public String sectorCode(String stockCode) {
        try {
            Technical technical = technical(stockCode);
            return technical.complete() ? technical.sectorCode() : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private Technical technical(String stockCode) {
        long now = System.currentTimeMillis();
        CachedTechnical cached = technicalCache.get(stockCode);
        if (cached != null && now - cached.capturedAt() < TECHNICAL_CACHE_MS) return cached.value();
        List<KiwoomTradeService.DailyCandle> candles =
                trade.getDailyChart(stockCode).block(Duration.ofSeconds(12));
        Technical calculated = calculateTechnical(candles);
        technicalCache.put(stockCode, new CachedTechnical(now, calculated));
        return calculated;
    }

    static Technical calculateTechnical(List<KiwoomTradeService.DailyCandle> candles) {
        int candleCount = candles == null ? 0 : candles.size();
        if (candleCount < 21) return new Technical(false, "", 0, 0, 0, 0, candleCount);
        double ma5 =
                candles.subList(0, 5).stream()
                        .mapToLong(KiwoomTradeService.DailyCandle::close)
                        .average()
                        .orElse(0);
        double ma20 =
                candles.subList(0, 20).stream()
                        .mapToLong(KiwoomTradeService.DailyCandle::close)
                        .average()
                        .orElse(0);
        double previousMa20 =
                candles.subList(1, 21).stream()
                        .mapToLong(KiwoomTradeService.DailyCandle::close)
                        .average()
                        .orElse(0);
        double trueRangeSum = 0;
        for (int i = 0; i < 14; i++) {
            KiwoomTradeService.DailyCandle current = candles.get(i);
            long previousClose = candles.get(i + 1).close();
            double trueRange =
                    Math.max(
                            current.high() - current.low(),
                            Math.max(
                                    Math.abs(current.high() - previousClose),
                                    Math.abs(current.low() - previousClose)));
            trueRangeSum += trueRange;
        }
        double atr = trueRangeSum / 14.0;
        double atrPercent = candles.get(0).close() <= 0 ? 0 : atr * 100.0 / candles.get(0).close();
        String sector =
                candles.stream()
                        .map(KiwoomTradeService.DailyCandle::sectorCode)
                        .filter(value -> value != null && !value.isBlank())
                        .findFirst()
                        .orElse("");
        return new Technical(
                ma5 > 0 && ma20 > 0 && previousMa20 > 0 && atrPercent > 0,
                sector,
                ma5,
                ma20,
                previousMa20,
                atrPercent,
                candleCount);
    }

    /** 종가 기준 최소 거래대금을 장중 누적 거래량 곡선에 맞춰 보정한다. */
    static long intradayTradingValueThreshold(long fullSessionThreshold, LocalTime time) {
        if (fullSessionThreshold <= 0) return 0;
        double fraction = KrxOpenApiService.expectedCumulativeVolumeFraction(time);
        return Math.max(1L, Math.round(fullSessionThreshold * fraction));
    }

    private static String format(double value) {
        return String.format("%.2f", value);
    }

    public record CandidateQuality(
            String stockCode,
            boolean accepted,
            boolean dataMissing,
            String gate,
            String detail,
            String sectorCode,
            double ma5,
            double ma20,
            double atrPercent,
            double priceAboveMa20Percent,
            double spreadPercent,
            long bestAsk) {
        static CandidateQuality missing(String code, String detail) {
            return new CandidateQuality(
                    code, false, true, "DATA_MISSING", detail, "", 0, 0, 0, 0, 0, 0);
        }

        static CandidateQuality rejected(String code, String gate, String detail) {
            return new CandidateQuality(code, false, false, gate, detail, "", 0, 0, 0, 0, 0, 0);
        }

        static CandidateQuality accepted(
                String code,
                String sector,
                double ma5,
                double ma20,
                double atr,
                double above,
                double spread,
                long ask) {
            return new CandidateQuality(
                    code,
                    true,
                    false,
                    "ACCEPTED",
                    "통과",
                    sector,
                    ma5,
                    ma20,
                    atr,
                    above,
                    spread,
                    ask);
        }
    }

    public record SpreadCheck(
            boolean accepted, long bid, long ask, double spreadPercent, String message) {}

    record Technical(
            boolean complete,
            String sectorCode,
            double ma5,
            double ma20,
            double previousMa20,
            double atrPercent,
            int candleCount) {}

    private record CachedTechnical(long capturedAt, Technical value) {}
}
