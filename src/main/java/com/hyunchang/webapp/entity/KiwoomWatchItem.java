package com.hyunchang.webapp.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity @Table(name = "kiwoom_watch_item")
public class KiwoomWatchItem {
 @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
 @Column(nullable=false, unique=true, length=6) private String stockCode;
 @Column(nullable=false) private String stockName;
 @Column(length=200) private String note;
 private LocalDateTime createdAt;
 @PrePersist void created() { createdAt = LocalDateTime.now(); }
 public Long getId(){return id;} public String getStockCode(){return stockCode;} public void setStockCode(String v){stockCode=v;}
 public String getStockName(){return stockName;} public void setStockName(String v){stockName=v;}
 public String getNote(){return note;} public void setNote(String v){note=v;} public LocalDateTime getCreatedAt(){return createdAt;}
}
