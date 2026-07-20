package com.hyunchang.webapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.hyunchang.webapp.entity.KiwoomStrategyControlState;
import com.hyunchang.webapp.repository.KiwoomStrategyControlStateRepository;
import com.hyunchang.webapp.util.KiwoomMarketHours;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KiwoomAutoTradeStateTest {

    @Mock private KiwoomStrategyControlStateRepository repo;
    @InjectMocks private KiwoomAutoTradeState state;

    @BeforeEach
    void setUp() {
        when(repo.findById(1L)).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void firstCheckCreatesSnapshotWithoutTriggering() {
        boolean newlyTriggered = state.recordDailyLossCheck(1_000_000, 50_000);

        assertFalse(newlyTriggered);
        assertFalse(state.isDailyLossTriggered());
        assertEquals(1_000_000, state.dailyLossStatus().baseAsset());
        assertEquals(0, state.dailyLossStatus().drawdown());
    }

    @Test
    void triggersOnceWhenDrawdownReachesLimit() {
        state.recordDailyLossCheck(1_000_000, 50_000);

        // 스냅샷 대비 5만원 하락 → 발동. 발동 알림은 최초 1회만 반환된다.
        assertTrue(state.recordDailyLossCheck(950_000, 50_000));
        assertTrue(state.isDailyLossTriggered());
        assertFalse(state.recordDailyLossCheck(940_000, 50_000));
        assertTrue(state.isDailyLossTriggered());
        assertEquals(60_000, state.dailyLossStatus().drawdown());
    }

    @Test
    void staysTriggeredEvenIfAssetRecovers() {
        state.recordDailyLossCheck(1_000_000, 50_000);
        state.recordDailyLossCheck(950_000, 50_000);

        // 자산이 회복돼도 당일 발동 상태는 유지된다 (다음 거래일에만 리셋).
        assertFalse(state.recordDailyLossCheck(1_000_000, 50_000));
        assertTrue(state.isDailyLossTriggered());
    }

    @Test
    void zeroLimitNeverTriggers() {
        state.recordDailyLossCheck(1_000_000, 0);

        assertFalse(state.recordDailyLossCheck(1, 0));
        assertFalse(state.isDailyLossTriggered());
    }

    @Test
    void staleSnapshotFromPreviousDayResetsAutomatically() {
        KiwoomStrategyControlState saved = new KiwoomStrategyControlState();
        saved.setDailyLossSnapshotDate(LocalDate.now(KiwoomMarketHours.KST).minusDays(1));
        saved.setDailyLossBaseAsset(1_000_000);
        saved.setDailyLossLastAsset(900_000);
        saved.setDailyLossTriggered(true);
        saved.setDailyLossLastCheckedAt(LocalDateTime.now().minusDays(1));
        when(repo.findById(1L)).thenReturn(Optional.of(saved));
        state.restoreControlState();

        // 어제 발동 상태는 오늘 기준으로는 해제된 것으로 본다.
        assertFalse(state.isDailyLossTriggered());

        // 오늘 첫 체크는 새 스냅샷을 잡는다 — 어제 기준자산과 무관하게 리셋.
        assertFalse(state.recordDailyLossCheck(900_000, 50_000));
        assertFalse(state.isDailyLossTriggered());
        assertEquals(900_000, state.dailyLossStatus().baseAsset());
    }
}
