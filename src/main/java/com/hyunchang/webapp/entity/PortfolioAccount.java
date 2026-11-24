package com.hyunchang.webapp.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** Account identity and purpose; never stores credentials or a broker account number. */
@Entity
@Table(
        name = "portfolio_account",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "account_key"}))
@Getter
@Setter
public class PortfolioAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "account_key", nullable = false, length = 50)
    private String accountKey;

    @Column(nullable = false, length = 20)
    private String broker;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false, length = 20)
    private String purpose;
}
