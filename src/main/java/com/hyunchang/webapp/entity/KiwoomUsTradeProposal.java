package com.hyunchang.webapp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "kiwoom_us_trade_proposals")
public class KiwoomUsTradeProposal {
    public enum Action {
        BUY,
        SELL
    }

    public enum Status {
        PROPOSED,
        ORDERED,
        PARTIALLY_FILLED,
        FILLED,
        CANCEL_REQUESTED,
        PARTIALLY_FILLED_CANCELED,
        CANCELED,
        FAILED,
        UNKNOWN
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private Action action;

    @Enumerated(EnumType.STRING)
    private Status status = Status.PROPOSED;

    @Column(length = 12, nullable = false)
    private String symbol;

    @Column(length = 2, nullable = false)
    private String exchange;

    private String stockName;
    private int quantity;

    @Column(precision = 19, scale = 4)
    private BigDecimal limitPrice;

    @Column(precision = 19, scale = 4)
    private BigDecimal averageFillPrice;

    private int filledQuantity;
    private int remainingQuantity;
    private String brokerOrderNo;

    @Column(length = 1000)
    private String reason;

    @Column(length = 1000)
    private String brokerResponse;

    private LocalDateTime orderedAt;
    private LocalDateTime cancelRequestedAt;
    private LocalDateTime createdAt;

    @PrePersist
    void created() {
        createdAt = LocalDateTime.now();
        remainingQuantity = quantity;
    }

    public void ordered(String orderNo, String response) {
        status = Status.ORDERED;
        brokerOrderNo = orderNo;
        brokerResponse = response;
        orderedAt = LocalDateTime.now();
    }

    public void failed(String response) {
        status = Status.FAILED;
        brokerResponse = response;
    }

    public void syncFill(int filled, int remaining, BigDecimal price) {
        filledQuantity = Math.max(filledQuantity, filled);
        remainingQuantity = Math.max(0, remaining);
        if (price != null && price.signum() > 0) averageFillPrice = price;
        if (remainingQuantity == 0 && filledQuantity > 0) {
            status = Status.FILLED;
        } else if (status != Status.CANCEL_REQUESTED) {
            status = filledQuantity > 0 ? Status.PARTIALLY_FILLED : Status.ORDERED;
        }
    }

    public void requestCancel() {
        status = Status.CANCEL_REQUESTED;
        cancelRequestedAt = LocalDateTime.now();
    }

    public void canceled() {
        status = filledQuantity > 0 ? Status.PARTIALLY_FILLED_CANCELED : Status.CANCELED;
        remainingQuantity = 0;
    }

    public void unknown(String response) {
        status = Status.UNKNOWN;
        brokerResponse = response;
        if (orderedAt == null) orderedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Action getAction() {
        return action;
    }

    public void setAction(Action v) {
        action = v;
    }

    public Status getStatus() {
        return status;
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

    public void setStockName(String v) {
        stockName = v;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int v) {
        quantity = v;
        remainingQuantity = v;
    }

    public BigDecimal getLimitPrice() {
        return limitPrice;
    }

    public void setLimitPrice(BigDecimal v) {
        limitPrice = v;
    }

    public BigDecimal getAverageFillPrice() {
        return averageFillPrice;
    }

    public int getFilledQuantity() {
        return filledQuantity;
    }

    public int getRemainingQuantity() {
        return remainingQuantity;
    }

    public String getBrokerOrderNo() {
        return brokerOrderNo;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String v) {
        reason = v;
    }

    public LocalDateTime getOrderedAt() {
        return orderedAt;
    }

    public LocalDateTime getCancelRequestedAt() {
        return cancelRequestedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
