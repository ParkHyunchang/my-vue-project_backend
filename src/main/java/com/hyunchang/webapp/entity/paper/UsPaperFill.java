package com.hyunchang.webapp.entity.paper;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "us_paper_fill")
@Getter
@Setter
public class UsPaperFill {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long sessionId;
    private Instant at;
    private String side;
    private String symbol = "SPY";
    private long quantity;

    @Column(precision = 24, scale = 8)
    private BigDecimal priceUsd;

    @Column(precision = 24, scale = 8)
    private BigDecimal feeUsd;

    private String reason;
}
