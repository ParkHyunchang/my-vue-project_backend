package com.hyunchang.webapp.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "kiwoom_us_account_risk")
public class KiwoomUsAccountRisk {
    @Id private Long id = 1L;
    @Version private Long version;
    private boolean enabled;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        enabled = value;
    }

    private boolean triggered;

    public boolean isTriggered() {
        return triggered;
    }

    public void setTriggered(boolean value) {
        triggered = value;
    }

    private boolean liquidationPending;

    public boolean isLiquidationPending() {
        return liquidationPending;
    }

    public void setLiquidationPending(boolean value) {
        liquidationPending = value;
    }

    private boolean capitalFlowReview;

    public boolean isCapitalFlowReview() {
        return capitalFlowReview;
    }

    public void setCapitalFlowReview(boolean value) {
        capitalFlowReview = value;
    }

    @Column(precision = 28, scale = 12)
    private BigDecimal units = BigDecimal.ZERO;

    public BigDecimal getUnits() {
        return units;
    }

    public void setUnits(BigDecimal value) {
        units = value;
    }

    @Column(precision = 28, scale = 12)
    private BigDecimal peakNav = BigDecimal.ZERO;

    public BigDecimal getPeakNav() {
        return peakNav;
    }

    public void setPeakNav(BigDecimal value) {
        peakNav = value;
    }

    @Column(precision = 28, scale = 12)
    private BigDecimal equityUsd = BigDecimal.ZERO;

    public BigDecimal getEquityUsd() {
        return equityUsd;
    }

    public void setEquityUsd(BigDecimal value) {
        equityUsd = value;
    }

    private LocalDateTime observedAt;

    public LocalDateTime getObservedAt() {
        return observedAt;
    }

    public void setObservedAt(LocalDateTime value) {
        observedAt = value;
    }
}
