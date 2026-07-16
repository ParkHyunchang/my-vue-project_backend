package com.hyunchang.webapp.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity @Table(name="kiwoom_strategy_control_state")
public class KiwoomStrategyControlState {
 @Id private Long id = 1L;
 private boolean emergencyStopped;
 private LocalDateTime updatedAt;
 @PrePersist @PreUpdate void touch(){updatedAt=LocalDateTime.now();}
 public boolean isEmergencyStopped(){return emergencyStopped;} public void setEmergencyStopped(boolean value){emergencyStopped=value;}
}
