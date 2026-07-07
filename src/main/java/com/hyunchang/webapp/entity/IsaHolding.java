package com.hyunchang.webapp.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "isa")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IsaHolding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonIgnore
    private User user;

    @Column(nullable = false, length = 2)
    private String market;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private Long quantity;

    @Column(name = "avg_price")
    private Double avgPrice;

    @Column(name = "is_core", nullable = false)
    private boolean core = false;

    @Column(name = "asset_type", nullable = false, length = 10)
    private String assetType = PortfolioAssetType.STOCK.name();

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (assetType == null || assetType.isBlank()) {
            assetType = PortfolioAssetType.STOCK.name();
        }
        if (PortfolioAssetType.CASH.name().equals(assetType)) {
            avgPrice = 1.0;
        }
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        if (PortfolioAssetType.CASH.name().equals(assetType)) {
            avgPrice = 1.0;
        }
        updatedAt = LocalDateTime.now();
    }
}
