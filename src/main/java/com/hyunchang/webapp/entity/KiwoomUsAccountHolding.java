package com.hyunchang.webapp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.LocalDateTime;

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
    private boolean firstTakeProfitCompleted;
    private LocalDateTime positionOpenedAt;
    private LocalDateTime syncedAt;

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

    public void markManagedByAutoTrade() {
        managedByAutoTrade = true;
    }

    public boolean isFirstTakeProfitCompleted() {
        return firstTakeProfitCompleted;
    }

    public void markFirstTakeProfitCompleted() {
        firstTakeProfitCompleted = true;
    }

    public void deactivate() {
        active = false;
        managedByAutoTrade = false;
        firstTakeProfitCompleted = false;
        quantity = 0;
        sellableQuantity = 0;
        positionOpenedAt = null;
        syncedAt = LocalDateTime.now();
    }

    public LocalDateTime getPositionOpenedAt() {
        return positionOpenedAt;
    }

    public LocalDateTime getSyncedAt() {
        return syncedAt;
    }
}
