package com.hyunchang.webapp.service.paper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Read-only prices. This boundary cannot submit or cancel a broker order. */
public interface UsPaperMarketFeed {
    MarketSnapshot fetch() throws Exception;

    record DailyBar(LocalDate date, BigDecimal close) {}

    record MarketSnapshot(
            BigDecimal price, Instant quotedAt, List<DailyBar> completedDailyBars, String source) {}
}
