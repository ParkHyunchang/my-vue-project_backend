package com.hyunchang.webapp.entity;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class KiwoomUsAccountHoldingTest {
    private KiwoomUsAccountHolding holding(int quantity) {
        var h = new KiwoomUsAccountHolding();
        update(h, quantity);
        return h;
    }

    private void update(KiwoomUsAccountHolding h, int quantity) {
        h.sync("Test", quantity, quantity, BigDecimal.TEN, BigDecimal.TEN, 0);
    }

    @Test
    void balanceBeforeFillRecoversOwnershipWithoutClaimingManualShares() {
        var h = holding(13);
        h.reconcileManagedQuantity(0, 0);
        assertFalse(h.isManagedByAutoTrade());
        h.reconcileManagedQuantity(3, 0);
        assertEquals(3, h.getManagedQuantity());
        h.reconcileManagedQuantity(3, 0);
        assertEquals(3, h.getManagedQuantity());
    }

    @Test
    void delayedSellFillIsNotSubtractedTwice() {
        var h = holding(10);
        h.reconcileManagedQuantity(10, 0);
        update(h, 5);
        h.reconcileManagedQuantity(10, 0, 5);
        h.reconcileManagedQuantity(10, 5);
        assertEquals(5, h.getManagedQuantity());
    }

    @Test
    void manualReductionCannotBeReclaimedByLaterManualBuy() {
        var h = holding(10);
        h.reconcileManagedQuantity(10, 0);
        update(h, 7);
        h.reconcileManagedQuantity(10, 0);
        update(h, 12);
        h.reconcileManagedQuantity(10, 0);
        assertEquals(7, h.getManagedQuantity());
    }

    @Test
    void fullyClosedPositionDoesNotClaimLaterManualHolding() {
        var h = holding(3);
        h.reconcileManagedQuantity(3, 0);
        update(h, 0);
        h.reconcileManagedQuantity(3, 0, 3);
        h.deactivate();
        update(h, 5);
        h.reconcileManagedQuantity(3, 3);
        assertEquals(0, h.getManagedQuantity());
        assertFalse(h.isManagedByAutoTrade());
    }

    @Test
    void manualReductionDoesNotOffsetAnUnrelatedFutureAutomaticSell() {
        var h = holding(10);
        h.reconcileManagedQuantity(10, 0);
        update(h, 7);
        h.reconcileManagedQuantity(10, 0);
        update(h, 12);
        h.reconcileManagedQuantity(10, 0);
        update(h, 10);
        h.reconcileManagedQuantity(10, 2);
        assertEquals(5, h.getManagedQuantity());
    }

    @Test
    void partialFirstProfitRetriesOnlyOriginalTargetRemainder() {
        var h = holding(10);
        h.reconcileManagedQuantity(10, 0);
        assertEquals(5, h.remainingFirstTakeProfitQuantity());
        update(h, 8);
        h.reconcileManagedQuantity(10, 2);
        h.reconcileFirstTakeProfit(2, 5);
        assertFalse(h.isFirstTakeProfitCompleted());
        assertEquals(3, h.remainingFirstTakeProfitQuantity());
        h.reconcileFirstTakeProfit(5, 5);
        assertTrue(h.isFirstTakeProfitCompleted());
        assertEquals(0, h.remainingFirstTakeProfitQuantity());
    }

    @Test
    void mixedManualPositionUsesAutomaticFillCostOrFailsClosed() {
        var h = holding(13);
        h.reconcileManagedQuantity(3, 0);
        assertTrue(Double.isNaN(h.managedProfitLossPercent()));
        h.setManagedAveragePrice(new BigDecimal("8"));
        assertEquals(25, h.managedProfitLossPercent(), 0.00001);
        h.deactivate();
        assertNull(h.getManagedAveragePrice());
    }
}
