package com.hyunchang.webapp.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** 키움 앱키에 연결된 실계좌의 최신 보유현황 스냅샷이다. 수동 포트폴리오와 분리한다. */
@Entity
@Table(
        name = "kiwoom_account_holding",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_kiwoom_account_holding_code",
                        columnNames = "stock_code"))
public class KiwoomAccountHolding {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_code", nullable = false, length = 6)
    private String stockCode;

    @Column(nullable = false, length = 100)
    private String stockName;

    private int quantity;
    private int sellableQuantity;
    private long averagePrice;
    private long currentPrice;
    private double profitLossPercent;
    private boolean active;
    private LocalDateTime syncedAt;

    public Long getId() {
        return id;
    }

    public String getStockCode() {
        return stockCode;
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

    public long getAveragePrice() {
        return averagePrice;
    }

    public long getCurrentPrice() {
        return currentPrice;
    }

    public double getProfitLossPercent() {
        return profitLossPercent;
    }

    public boolean isActive() {
        return active;
    }

    public LocalDateTime getSyncedAt() {
        return syncedAt;
    }

    public void updateFrom(
            String stockCode,
            String stockName,
            int quantity,
            int sellableQuantity,
            long averagePrice,
            long currentPrice,
            double profitLossPercent,
            LocalDateTime syncedAt) {
        this.stockCode = stockCode;
        this.stockName = stockName;
        this.quantity = quantity;
        this.sellableQuantity = sellableQuantity;
        this.averagePrice = averagePrice;
        this.currentPrice = currentPrice;
        this.profitLossPercent = profitLossPercent;
        this.active = true;
        this.syncedAt = syncedAt;
    }

    public void markInactive(LocalDateTime syncedAt) {
        active = false;
        quantity = 0;
        sellableQuantity = 0;
        this.syncedAt = syncedAt;
    }
}
