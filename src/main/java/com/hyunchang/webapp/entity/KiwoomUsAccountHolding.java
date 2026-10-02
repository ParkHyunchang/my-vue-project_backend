package com.hyunchang.webapp.entity;

import com.hyunchang.webapp.util.KiwoomUsMarketHours;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;

@Entity
@Table(
        name = "kiwoom_us_account_holdings",
        uniqueConstraints = @UniqueConstraint(columnNames = {"exchange", "symbol"}))
public class KiwoomUsAccountHolding {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 12)
    private String symbol;

    @Column(nullable = false, length = 2)
    private String exchange;

    private String stockName;
    private int quantity;
    private int sellableQuantity;

    @Column(precision = 19, scale = 4)
    private BigDecimal averagePrice;

    @Column(precision = 19, scale = 4)
    private BigDecimal currentPrice;

    private double profitLossPercent;
    private boolean active;
    private boolean managedByAutoTrade;
    private int managedQuantity;
    private Long accountedBuyQuantity;
    private Long accountedSellQuantity;
    private long pendingQuantityReduction;
    private Double plannedStopLossPercent;

    @Column(precision = 19, scale = 4)
    private BigDecimal managedAveragePrice;

    private int firstTakeProfitTargetQuantity;
    private int firstTakeProfitFilledQuantity;
    private boolean firstTakeProfitCompleted;
    private LocalDateTime positionOpenedAt;
    private LocalDateTime lastClosedAt;
    private LocalDateTime syncedAt;
    @Embedded private KiwoomUsTrendExitPlan trendExitPlan;
    private Long trendEntryProposalId;
    private LocalDate trendStartedOn;

    @Column(precision = 19, scale = 4)
    private BigDecimal trendHighWaterPrice;

    @Column(precision = 19, scale = 4)
    private BigDecimal trendStopPrice;

    public KiwoomUsTrendExitPlan getTrendExitPlan() {
        return trendExitPlan;
    }

    public LocalDate getTrendStartedOn() {
        return trendStartedOn;
    }

    public BigDecimal getTrendHighWaterPrice() {
        return trendHighWaterPrice;
    }

    public BigDecimal getTrendStopPrice() {
        return trendStopPrice;
    }

    public void restoreTrendExitPlan(KiwoomUsTradeProposal buy) {
        if (buy.getTrendExitPlan() == null) return;
        if (trendExitPlan != null && Objects.equals(trendEntryProposalId, buy.getId())) return;
        trendExitPlan = buy.getTrendExitPlan().copy();
        trendEntryProposalId = buy.getId();
        // First observed confirmed fill: never count manual holding age as strategy holding age.
        trendStartedOn =
                buy.getFirstFilledAt() == null
                        ? null
                        : buy.getFirstFilledAt()
                                .atZone(ZoneId.systemDefault())
                                .withZoneSameInstant(KiwoomUsMarketHours.ET)
                                .toLocalDate();
        trendHighWaterPrice = null;
        trendStopPrice = null;
    }

    public void updateTrendStop() {
        if (trendExitPlan == null
                || !trendExitPlan.isValid()
                || managedAveragePrice == null
                || managedAveragePrice.signum() <= 0
                || currentPrice == null
                || currentPrice.signum() <= 0)
            throw new IllegalStateException("추세 청산 계획·자동매매 원가·시세 확인 필요");
        BigDecimal risk = BigDecimal.valueOf(trendExitPlan.getTrendRiskPerShare());
        BigDecimal initialStop = managedAveragePrice.subtract(risk);
        trendHighWaterPrice =
                (trendHighWaterPrice == null ? managedAveragePrice : trendHighWaterPrice)
                        .max(currentPrice);
        trendStopPrice = (trendStopPrice == null ? initialStop : trendStopPrice).max(initialStop);
        if (trendHighWaterPrice.compareTo(
                        managedAveragePrice.add(
                                risk.multiply(
                                        BigDecimal.valueOf(
                                                trendExitPlan.getTrendTrailActivationR()))))
                >= 0) {
            BigDecimal trailing =
                    trendHighWaterPrice.subtract(
                            BigDecimal.valueOf(
                                    trendExitPlan.getTrendEntryAtr()
                                            * trendExitPlan.getTrendTrailAtrMultiplier()));
            trendStopPrice = trendStopPrice.max(trailing);
        }
        trendStopPrice = trendStopPrice.setScale(4, java.math.RoundingMode.DOWN);
    }

    public void sync(
            String name, int qty, int sellable, BigDecimal avg, BigDecimal current, double pnl) {
        stockName = name;
        quantity = qty;
        sellableQuantity = sellable;
        averagePrice = avg;
        currentPrice = current;
        profitLossPercent = pnl;
        active = qty > 0;
        if (active && positionOpenedAt == null) positionOpenedAt = LocalDateTime.now();
        syncedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String v) {
        symbol = v;
    }

    public String getExchange() {
        return exchange;
    }

    public void setExchange(String v) {
        exchange = v;
    }

    public String getStockName() {
        return stockName;
    }

    public int getQuantity() {
        return quantity;
    }

    public int getSellableQuantity() {
        return sellableQuantity;
    }

    public BigDecimal getAveragePrice() {
        return averagePrice;
    }

    public BigDecimal getCurrentPrice() {
        return currentPrice;
    }

    public double getProfitLossPercent() {
        return profitLossPercent;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isManagedByAutoTrade() {
        return managedByAutoTrade;
    }

    public int getManagedQuantity() {
        return Math.min(quantity, managedQuantity);
    }

    public Double getPlannedStopLossPercent() {
        return plannedStopLossPercent;
    }

    public void setPlannedStopLossPercent(Double value) {
        plannedStopLossPercent = value;
    }

    public boolean hasOwnershipCounters() {
        return accountedBuyQuantity != null && accountedSellQuantity != null;
    }

    public void initializeUnmanagedBaseline(long buys, long sells) {
        accountedBuyQuantity = buys;
        accountedSellQuantity = sells;
    }

    public void setManagedAveragePrice(BigDecimal value) {
        managedAveragePrice = value;
    }

    public BigDecimal getManagedAveragePrice() {
        return managedAveragePrice;
    }

    public double managedProfitLossPercent() {
        BigDecimal basis = managedAveragePrice;
        if (basis == null && managedQuantity == quantity) basis = averagePrice;
        if (basis == null
                || basis.signum() <= 0
                || currentPrice == null
                || currentPrice.signum() <= 0) return Double.NaN;
        return currentPrice
                        .divide(basis, 10, java.math.RoundingMode.HALF_UP)
                        .subtract(BigDecimal.ONE)
                        .doubleValue()
                * 100;
    }

    public int remainingFirstTakeProfitQuantity() {
        if (firstTakeProfitTargetQuantity == 0)
            firstTakeProfitTargetQuantity = Math.max(1, managedQuantity / 2);
        return Math.max(0, firstTakeProfitTargetQuantity - firstTakeProfitFilledQuantity);
    }

    public void reconcileFirstTakeProfit(int filled, int initialTarget) {
        if (firstTakeProfitTargetQuantity == 0 && initialTarget > 0)
            firstTakeProfitTargetQuantity = initialTarget;
        firstTakeProfitFilledQuantity = filled;
        firstTakeProfitCompleted =
                firstTakeProfitTargetQuantity > 0 && filled >= firstTakeProfitTargetQuantity;
    }

    /** Cumulative confirmed fills make replay idempotent, including delayed/partial fills. */
    public void reconcileManagedQuantity(long buys, long sells) {
        reconcileManagedQuantity(buys, sells, 0);
    }

    public void reconcileManagedQuantity(long buys, long sells, long outstandingAutomaticSells) {
        long owned;
        if (accountedBuyQuantity == null || accountedSellQuantity == null) {
            owned = Math.max(0, buys - sells);
        } else {
            long newSells = Math.max(0, sells - accountedSellQuantity);
            long alreadyObserved = Math.min(pendingQuantityReduction, newSells);
            pendingQuantityReduction -= alreadyObserved;
            owned =
                    (long) managedQuantity
                            + Math.max(0, buys - accountedBuyQuantity)
                            - (newSells - alreadyObserved);
        }
        // A manual reduction is respected and will not be reclaimed on the next refresh.
        // Only an outstanding automatic sell can explain a delayed sell fill.
        // Manual reductions must not become credits against unrelated future sells.
        pendingQuantityReduction =
                Math.min(
                        Math.max(0, outstandingAutomaticSells),
                        pendingQuantityReduction + Math.max(0, owned - quantity));
        managedQuantity = (int) Math.min(quantity, Math.max(0, owned));
        accountedBuyQuantity = buys;
        accountedSellQuantity = sells;
        managedByAutoTrade = managedQuantity > 0;
    }

    public void markManagedByAutoTrade() {
        managedByAutoTrade = true;
        managedQuantity = quantity;
    }

    public boolean isFirstTakeProfitCompleted() {
        return firstTakeProfitCompleted;
    }

    public void markFirstTakeProfitCompleted() {
        firstTakeProfitCompleted = true;
    }

    public void deactivate() {
        lastClosedAt = LocalDateTime.now();
        active = false;
        managedByAutoTrade = false;
        managedQuantity = 0;
        firstTakeProfitCompleted = false;
        quantity = 0;
        sellableQuantity = 0;
        positionOpenedAt = null;
        plannedStopLossPercent = null;
        managedAveragePrice = null;
        trendExitPlan = null;
        trendEntryProposalId = null;
        trendStartedOn = null;
        trendHighWaterPrice = null;
        trendStopPrice = null;
        firstTakeProfitTargetQuantity = 0;
        firstTakeProfitFilledQuantity = 0;
        syncedAt = LocalDateTime.now();
    }

    public LocalDateTime getPositionOpenedAt() {
        return positionOpenedAt;
    }

    public LocalDateTime getLastClosedAt() {
        return lastClosedAt;
    }

    public LocalDateTime getSyncedAt() {
        return syncedAt;
    }
}
