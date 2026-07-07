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
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 여행 버킷리스트 — 가고 싶은 곳. 사용자가 수동 입력한다.
 * AI 플래너에서 "버킷리스트에 담기"로 추가되기도 한다.
 */
@Entity
@Table(name = "travel_wishlist")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TravelWishlist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonIgnore
    private User user;

    @Column(nullable = false)
    private String title;      // 가고 싶은 곳 (예: 오사카, 산토리니)

    @Column(length = 60)
    private String country;    // 국가 (예: 일본)

    @Column(length = 60)
    private String city;       // 도시·지역 (예: 오사카)

    // 우선순위 1(높음) ~ 3(낮음). 기본 2.
    @Column(name = "priority")
    private Integer priority = 2;

    @Column(name = "target_period", length = 40)
    private String targetPeriod; // 목표 시기 (예: 2026 여름)

    // 예상 예산 (만원)
    @Column(name = "est_budget")
    private Long estBudget;

    @Column(length = 1000)
    private String memo;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
