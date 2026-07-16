package com.hyunchang.webapp.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity @Table(name="kiwoom_strategy_run")
public class KiwoomStrategyRun {
 public enum Status { SUCCESS, FAILED, PARSE_FAILED, BLOCKED } public enum TriggeredBy { MANUAL, SCHEDULE }
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Enumerated(EnumType.STRING) @Column(nullable=false) private TriggeredBy triggeredBy;
 @Enumerated(EnumType.STRING) @Column(nullable=false) private Status status;
 private String providerName; private String model; @Column(columnDefinition="TEXT") private String marketView;
 @Column(length=500) private String errorMessage; private int promptChars; private LocalDateTime createdAt;
 @PrePersist void created(){createdAt=LocalDateTime.now();}
 public Long getId(){return id;} public TriggeredBy getTriggeredBy(){return triggeredBy;} public void setTriggeredBy(TriggeredBy v){triggeredBy=v;}
 public Status getStatus(){return status;} public void setStatus(Status v){status=v;} public String getProviderName(){return providerName;} public void setProviderName(String v){providerName=v;}
 public String getModel(){return model;} public void setModel(String v){model=v;} public String getMarketView(){return marketView;} public void setMarketView(String v){marketView=v;}
 public String getErrorMessage(){return errorMessage;} public void setErrorMessage(String v){errorMessage=v;} public int getPromptChars(){return promptChars;} public void setPromptChars(int v){promptChars=v;} public LocalDateTime getCreatedAt(){return createdAt;}
}
