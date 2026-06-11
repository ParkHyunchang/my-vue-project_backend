package com.hyunchang.webapp.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 보유 부동산(아파트). 사용자가 수동 입력한다.
 * 시세는 lawdCd + name(단지명) + areaM2 로 국토부 실거래가와 매칭해 추정한다.
 */
@Entity
@Table(name = "property")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PropertyHolding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonIgnore
    private User user;

    @Column(name = "deal_type", nullable = false, length = 10)
    private String dealType; // SALE / JEONSE / MONTHLY

    @Column(nullable = false)
    private String name;     // 단지명 (예: 래미안대치팰리스)

    @Column(name = "lawd_cd", nullable = false, length = 5)
    private String lawdCd;   // 법정동 시군구 코드

    @Column(length = 60)
    private String sigungu;  // 시군구 표시명 (예: 강남구)

    @Column(name = "area_m2")
    private Double areaM2;    // 전용면적(㎡)

    // 매입가(매매) 또는 보증금(전세/월세). 단위: 만원
    @Column(name = "purchase_price")
    private Long purchasePrice;

    // 매입(계약) 일자 — 보유기간/등락률 표시용
    @Column(name = "purchase_date")
    private java.time.LocalDate purchaseDate;

    // 월세(만원) — 월세 거래에만 사용
    @Column(name = "monthly_rent")
    private Long monthlyRent;

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
