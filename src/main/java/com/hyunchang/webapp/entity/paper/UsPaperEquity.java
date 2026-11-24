package com.hyunchang.webapp.entity.paper;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "us_paper_equity")
@Getter
@Setter
public class UsPaperEquity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long sessionId;
    private Instant at;

    @Column(precision = 24, scale = 8)
    private BigDecimal equityUsd;

    @Column(precision = 20, scale = 10)
    private BigDecimal strategyReturnPercent;

    @Column(precision = 20, scale = 10)
    private BigDecimal benchmarkReturnPercent;
}
