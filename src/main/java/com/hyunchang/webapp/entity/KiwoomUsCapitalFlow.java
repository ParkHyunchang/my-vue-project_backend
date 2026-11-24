package com.hyunchang.webapp.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "kiwoom_us_capital_flow")
public class KiwoomUsCapitalFlow {
    @Id
    @Column(length = 100)
    private String reference;

    @Column(precision = 24, scale = 8)
    private BigDecimal amountUsd;

    private LocalDateTime recordedAt;

    protected KiwoomUsCapitalFlow() {}

    public KiwoomUsCapitalFlow(String reference, BigDecimal amountUsd) {
        this.reference = reference;
        this.amountUsd = amountUsd;
        this.recordedAt = LocalDateTime.now();
    }

    public BigDecimal getAmountUsd() {
        return amountUsd;
    }
}
