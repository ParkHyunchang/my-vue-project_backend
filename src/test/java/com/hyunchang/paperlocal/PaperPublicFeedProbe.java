package com.hyunchang.paperlocal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.service.paper.UsPaperYahooFeedService;
import java.time.Clock;

/** Explicit public read-only smoke probe. No Spring, database, credentials or order path. */
public final class PaperPublicFeedProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 0)
            throw new IllegalArgumentException("No URL or configuration overrides");
        var snapshot = new UsPaperYahooFeedService(new ObjectMapper(), Clock.systemUTC()).fetch();
        System.out.println(
                "PUBLIC_FEED_READ_OK source="
                        + snapshot.source()
                        + " quote="
                        + snapshot.price()
                        + " quotedAt="
                        + snapshot.quotedAt()
                        + " completedDailyBars="
                        + snapshot.completedDailyBars().size()
                        + " lastCompletedDate="
                        + snapshot.completedDailyBars().getLast().date());
    }
}
