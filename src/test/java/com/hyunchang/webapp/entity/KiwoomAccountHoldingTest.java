package com.hyunchang.webapp.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class KiwoomAccountHoldingTest {

    @Test
    void positionOpenedAtIsPreservedWhileSameHoldingRemainsActive() {
        KiwoomAccountHolding holding = new KiwoomAccountHolding();
        LocalDateTime firstSeen = LocalDateTime.of(2026, 8, 7, 9, 1);
        LocalDateTime nextSync = firstSeen.plusHours(2);

        holding.updateFrom("005930", "삼성전자", 1, 1, 70_000, 71_000, 1.4, firstSeen);
        holding.updateFrom("005930", "삼성전자", 2, 2, 70_500, 71_500, 1.4, nextSync);

        assertEquals(firstSeen, holding.getPositionOpenedAt());
    }

    @Test
    void reopenedHoldingGetsANewPositionStartTime() {
        KiwoomAccountHolding holding = new KiwoomAccountHolding();
        LocalDateTime firstSeen = LocalDateTime.of(2026, 8, 7, 9, 1);
        LocalDateTime reopened = firstSeen.plusDays(5);

        holding.updateFrom("005930", "삼성전자", 1, 1, 70_000, 71_000, 1.4, firstSeen);
        holding.markInactive(firstSeen.plusDays(1));
        holding.updateFrom("005930", "삼성전자", 1, 1, 72_000, 72_000, 0, reopened);

        assertEquals(reopened, holding.getPositionOpenedAt());
    }
}
