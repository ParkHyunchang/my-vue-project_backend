package com.hyunchang.webapp.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 보유 부동산. 사용자가 수동 입력한다. propertyType 으로 아파트/토지를 구분한다.
 * - APT(아파트):  lawdCd + name(단지명) + areaM2(전용면적) 로 실거래가와 매칭해 시세 추정.
 * - LAND(토지):   lawdCd + jimok(지목) + useZone(용도지역) 로 단가(원/㎡)를 추정하고
 *                 areaM2(토지면적)·officialPrice(공시지가) 를 함께 사용한다.
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

    // 부동산 유형: APT(아파트) | LAND(토지). 기존 행은 APT 로 간주.
    @Column(name = "property_type", length = 10)
    private String propertyType = "APT";

    @Column(name = "deal_type", nullable = false, length = 10)
    private String dealType; // SALE / JEONSE / MONTHLY (토지는 항상 SALE)

    @Column(nullable = false)
    private String name;     // 아파트=단지명 / 토지=별칭·지번 라벨 (예: 역삼동 123-4)

    @Column(name = "lawd_cd", nullable = false, length = 5)
    private String lawdCd;   // 법정동 시군구 코드

    @Column(length = 60)
    private String sigungu;  // 시군구 표시명 (예: 강남구)

    @Column(name = "area_m2")
    private Double areaM2;    // 아파트=전용면적 / 토지=토지면적 (㎡)

    // ── 토지(LAND) 전용 필드 ──────────────────────────────────────
    @Column(length = 20)
    private String jimok;     // 지목 (전/답/대/임야 등)

    @Column(name = "use_zone", length = 40)
    private String useZone;   // 용도지역 (계획관리/주거지역/농림 등)

    @Column(name = "umd_name", length = 60)
    private String umdName;   // 읍면동 (예: 역삼동) — 공시지가 조회/표시용

    @Column(length = 30)
    private String jibun;     // 지번 (본번-부번, 예: 123-4 / 산 12-3)

    @Column(name = "bdong_code", length = 10)
    private String bdongCode; // 법정동코드(10자리) — PNU 조합·공시지가 재조회용

    @Column(name = "official_price_per_m2")
    private Long officialPricePerM2; // 개별공시지가(원/㎡) — Phase L2

    @Column(name = "official_price_year")
    private Integer officialPriceYear; // 공시지가 기준연도 — Phase L2

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
