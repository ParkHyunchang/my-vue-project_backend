package com.hyunchang.webapp.service.paper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.ArrayList;
import java.util.List;

/** A single fixed, unauthenticated GET endpoint; no account credentials or order API. */
public class UsPaperYahooFeedService implements UsPaperMarketFeed {
    private static final String URL =
            "https://query1.finance.yahoo.com/v8/finance/chart/SPY?range=2y&interval=1d";
    private final ObjectMapper mapper;
    private final Clock clock;

    public UsPaperYahooFeedService(ObjectMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public MarketSnapshot fetch() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(URL).toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 PaperResearch/1.0");
        try {
            if (connection.getResponseCode() != 200)
                throw new IllegalStateException("공개 시세 제공자가 응답하지 않았습니다.");
            byte[] bytes = connection.getInputStream().readNBytes(2_000_001);
            if (bytes.length > 2_000_000) throw new IllegalStateException("시세 응답이 너무 큽니다.");
            return parse(new String(bytes, StandardCharsets.UTF_8), clock.instant());
        } finally {
            connection.disconnect();
        }
    }

    public MarketSnapshot parse(String raw, Instant now) throws Exception {
        JsonNode result = mapper.readTree(raw).path("chart").path("result").path(0),
                meta = result.path("meta");
        if (!"SPY".equals(meta.path("symbol").asText())
                || !"USD".equals(meta.path("currency").asText()))
            throw new IllegalArgumentException("시세 종목/통화가 일치하지 않습니다.");
        if (!meta.path("regularMarketPrice").isNumber()
                || !meta.path("regularMarketTime").canConvertToLong())
            throw new IllegalArgumentException("시세/시각이 누락되었습니다.");
        BigDecimal price = meta.path("regularMarketPrice").decimalValue();
        Instant quoteTime = Instant.ofEpochSecond(meta.path("regularMarketTime").longValue());
        JsonNode times = result.path("timestamp"),
                closes = result.path("indicators").path("quote").path(0).path("close");
        if (!times.isArray() || times.size() != closes.size())
            throw new IllegalArgumentException("일봉 자료가 불완전합니다.");
        List<DailyBar> bars = new ArrayList<>();
        LocalDate today = now.atZone(UsPaperMarketService.ET).toLocalDate(), previous = null;
        for (int i = 0; i < times.size(); i++) {
            LocalDate day =
                    Instant.ofEpochSecond(times.get(i).longValue())
                            .atZone(UsPaperMarketService.ET)
                            .toLocalDate();
            if (!day.isBefore(today)) continue;
            if (!closes.get(i).isNumber()
                    || closes.get(i).decimalValue().signum() <= 0
                    || previous != null && !day.isAfter(previous))
                throw new IllegalArgumentException("완료 일봉 가격/순서가 잘못되었습니다.");
            bars.add(new DailyBar(day, closes.get(i).decimalValue()));
            previous = day;
        }
        if (price.signum() <= 0) throw new IllegalArgumentException("유효하지 않은 시세입니다.");
        return new MarketSnapshot(
                price, quoteTime, List.copyOf(bars), "YAHOO_PUBLIC_DELAY_POSSIBLE");
    }
}
