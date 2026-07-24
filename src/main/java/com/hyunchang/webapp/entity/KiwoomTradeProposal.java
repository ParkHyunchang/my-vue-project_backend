package com.hyunchang.webapp.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "kiwoom_trade_proposal")
public class KiwoomTradeProposal {
    public enum Action {
        BUY,
        SELL,
        HOLD
    }

    public enum OrderType {
        LIMIT,
        MARKET
    }

    public enum Status {
        PROPOSED,
        APPROVED,
        REJECTED,
        ORDER_DRAFT,
        ORDERED,
        PARTIALLY_FILLED,
        CANCEL_REQUESTED,
        FILLED,
        CANCELED,
        ORDER_FAILED,
        ORDER_UNKNOWN
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 응답 직렬화 시 run 전체가 제안마다 중복 포함되는 것을 막는다 (run 정보는 /runs 응답의 상위 레벨에 있음).
    @com.fasterxml.jackson.annotation.JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id")
    private KiwoomStrategyRun run;

    @Enumerated(EnumType.STRING)
    private Action action;

    @Column(length = 6)
    private String stockCode;

    private String stockName;
    private int quantity;
    private Long stopLossPrice;
    private Long takeProfitPrice;
    private Integer maxHoldingDays;

    @Enumerated(EnumType.STRING)
    private OrderType orderType;

    private Long limitPrice;
    private int confidence;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(columnDefinition = "TEXT")
    private String guardFlags;

    @Enumerated(EnumType.STRING)
    private Status status = Status.PROPOSED;

    private LocalDateTime approvedAt;
    private LocalDateTime rejectedAt;
    private LocalDateTime draftedAt;
    private LocalDateTime orderedAt;
    private LocalDateTime amendedAt;
    private LocalDateTime cancelRequestedAt;
    private LocalDateTime lastSyncedAt;

    @Column(length = 30)
    private String brokerOrderNo;

    private int filledQuantity;
    private int remainingQuantity;
    private Long averageFillPrice;

    @Column(length = 500)
    private String rejectionReason;

    @Column(length = 500)
    private String errorMessage;

    @Column(columnDefinition = "TEXT")
    private String brokerResponse;

    private LocalDateTime createdAt;

    @PrePersist
    void created() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public KiwoomStrategyRun getRun() {
        return run;
    }

    public void setRun(KiwoomStrategyRun v) {
        run = v;
    }

    public Action getAction() {
        return action;
    }

    public void setAction(Action v) {
        action = v;
    }

    public String getStockCode() {
        return stockCode;
    }

    public void setStockCode(String v) {
        stockCode = v;
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
    }

    public Long getStopLossPrice() {
        return stopLossPrice;
    }

    public void setStopLossPrice(Long v) {
        stopLossPrice = v;
    }

    public Long getTakeProfitPrice() {
        return takeProfitPrice;
    }

    public void setTakeProfitPrice(Long v) {
        takeProfitPrice = v;
    }

    public Integer getMaxHoldingDays() {
        return maxHoldingDays;
    }

    public void setMaxHoldingDays(Integer v) {
        maxHoldingDays = v;
    }

    public OrderType getOrderType() {
        return orderType;
    }

    public void setOrderType(OrderType v) {
        orderType = v;
    }

    public Long getLimitPrice() {
        return limitPrice;
    }

    public void setLimitPrice(Long v) {
        limitPrice = v;
    }

    public int getConfidence() {
        return confidence;
    }

    public void setConfidence(int v) {
        confidence = v;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String v) {
        reason = v;
    }

    public String getGuardFlags() {
        return guardFlags;
    }

    public void setGuardFlags(String v) {
        guardFlags = v;
    }

    public Status getStatus() {
        return status;
    }

    public LocalDateTime getApprovedAt() {
        return approvedAt;
    }

    public LocalDateTime getRejectedAt() {
        return rejectedAt;
    }

    public LocalDateTime getDraftedAt() {
        return draftedAt;
    }

    public LocalDateTime getOrderedAt() {
        return orderedAt;
    }

    public LocalDateTime getAmendedAt() {
        return amendedAt;
    }

    public LocalDateTime getCancelRequestedAt() {
        return cancelRequestedAt;
    }

    public LocalDateTime getLastSyncedAt() {
        return lastSyncedAt;
    }

    public String getBrokerOrderNo() {
        return brokerOrderNo;
    }

    public int getFilledQuantity() {
        return filledQuantity;
    }

    public int getRemainingQuantity() {
        return remainingQuantity;
    }

    public Long getAverageFillPrice() {
        return averageFillPrice;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getBrokerResponse() {
        return brokerResponse;
    }

    public void approve() {
        status = Status.APPROVED;
        approvedAt = LocalDateTime.now();
    }

    public void reject(String reason) {
        status = Status.REJECTED;
        rejectedAt = LocalDateTime.now();
        rejectionReason = reason;
    }

    public void draft() {
        status = Status.ORDER_DRAFT;
        draftedAt = LocalDateTime.now();
    }

    public void ordered(String response, String orderNo) {
        status = Status.ORDERED;
        orderedAt = LocalDateTime.now();
        brokerResponse = response;
        brokerOrderNo = orderNo;
        remainingQuantity = quantity;
    }

    public void amended(int newQuantity, Long newPrice, String response, String orderNo) {
        quantity = newQuantity;
        limitPrice = newPrice;
        brokerResponse = response;
        if (orderNo != null && !orderNo.isBlank()) brokerOrderNo = orderNo;
        remainingQuantity = Math.max(0, newQuantity - filledQuantity);
        amendedAt = LocalDateTime.now();
        status = filledQuantity > 0 ? Status.PARTIALLY_FILLED : Status.ORDERED;
    }

    public void cancelRequested(String response) {
        brokerResponse = response;
        cancelRequestedAt = LocalDateTime.now();
        status = Status.CANCEL_REQUESTED;
    }

    public void cancelled() {
        remainingQuantity = 0;
        lastSyncedAt = LocalDateTime.now();
        status = Status.CANCELED;
    }

    public void syncFill(int filled, int remaining, Long averagePrice) {
        filledQuantity = Math.max(0, filled);
        remainingQuantity = Math.max(0, remaining);
        averageFillPrice = averagePrice;
        lastSyncedAt = LocalDateTime.now();
        status =
                remainingQuantity == 0 && filledQuantity > 0
                        ? Status.FILLED
                        : (filledQuantity > 0 ? Status.PARTIALLY_FILLED : Status.ORDERED);
    }

    public void orderFailed(String error) {
        status = Status.ORDER_FAILED;
        errorMessage = error;
    }

    public void orderUnknown(String response) {
        status = Status.ORDER_UNKNOWN;
        brokerResponse = response;
        errorMessage = "Broker response did not include an order number.";
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void updateDraft(int quantity, Long limitPrice) {
        this.quantity = quantity;
        this.limitPrice = limitPrice;
        this.orderType = OrderType.LIMIT;
    }
}
