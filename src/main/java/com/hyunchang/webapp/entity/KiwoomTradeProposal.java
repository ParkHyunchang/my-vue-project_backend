package com.hyunchang.webapp.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity @Table(name="kiwoom_trade_proposal")
public class KiwoomTradeProposal {
 public enum Action { BUY, SELL, HOLD } public enum OrderType { LIMIT, MARKET } public enum Status { PROPOSED }
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="run_id") private KiwoomStrategyRun run;
 @Enumerated(EnumType.STRING) private Action action; @Column(length=6) private String stockCode; private String stockName; private int quantity;
 @Enumerated(EnumType.STRING) private OrderType orderType; private Long limitPrice; private int confidence; @Column(columnDefinition="TEXT") private String reason;
 @Column(columnDefinition="TEXT") private String guardFlags; @Enumerated(EnumType.STRING) private Status status=Status.PROPOSED; private LocalDateTime createdAt;
 @PrePersist void created(){createdAt=LocalDateTime.now();}
 public Long getId(){return id;} public KiwoomStrategyRun getRun(){return run;} public void setRun(KiwoomStrategyRun v){run=v;} public Action getAction(){return action;} public void setAction(Action v){action=v;}
 public String getStockCode(){return stockCode;} public void setStockCode(String v){stockCode=v;} public String getStockName(){return stockName;} public void setStockName(String v){stockName=v;} public int getQuantity(){return quantity;} public void setQuantity(int v){quantity=v;}
 public OrderType getOrderType(){return orderType;} public void setOrderType(OrderType v){orderType=v;} public Long getLimitPrice(){return limitPrice;} public void setLimitPrice(Long v){limitPrice=v;} public int getConfidence(){return confidence;} public void setConfidence(int v){confidence=v;}
 public String getReason(){return reason;} public void setReason(String v){reason=v;} public String getGuardFlags(){return guardFlags;} public void setGuardFlags(String v){guardFlags=v;} public Status getStatus(){return status;} public LocalDateTime getCreatedAt(){return createdAt;}
}
