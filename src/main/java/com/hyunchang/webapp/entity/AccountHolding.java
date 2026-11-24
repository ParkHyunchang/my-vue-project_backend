package com.hyunchang.webapp.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(
        name = "portfolio_account_holding",
        uniqueConstraints = @UniqueConstraint(columnNames = {"account_id", "market", "symbol"}))
@Getter
@Setter
public class AccountHolding {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private PortfolioAccount account;

    @Column(nullable = false, length = 10)
    private String assetType;

    @Column(nullable = false, length = 2)
    private String market;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, precision = 24, scale = 8)
    private BigDecimal quantity;

    @Column(precision = 24, scale = 8)
    private BigDecimal avgPrice;

    @Column(nullable = false)
    private boolean core;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void recordTime() {
        updatedAt = Instant.now();
    }
}
