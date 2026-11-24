package com.hyunchang.webapp.entity.paper;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "us_paper_session")
@Getter
@Setter
public class UsPaperSession {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version private Long version;
    private String state = "CREATED";

    @Column(precision = 24, scale = 8)
    private BigDecimal initialCashUsd;

    @Column(precision = 24, scale = 8)
    private BigDecimal cashUsd;

    private long quantity;

    @Column(precision = 24, scale = 8)
    private BigDecimal lastPriceUsd;

    @Column(precision = 24, scale = 8)
    private BigDecimal equityUsd;

    @Column(precision = 24, scale = 8)
    private BigDecimal peakEquityUsd;

    @Column(precision = 20, scale = 10)
    private BigDecimal maxDrawdownPercent = BigDecimal.ZERO;

    @Column(precision = 24, scale = 8)
    private BigDecimal benchmarkBase;

    private boolean hardLatched;
    private Instant createdAt;
    private Instant startedAt;
    private Instant observedAt;
    private Instant quotedAt;
    private Instant lastProcessedQuote;
    private LocalDate lastSignalDate;
    private String reason = "CREATED";

    @Column(length = 600)
    private String message = "가상 세션을 생성했습니다. 실제 주문은 전송하지 않습니다.";

    private String source = "NONE";
}
