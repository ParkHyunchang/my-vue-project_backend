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

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 다녀온 곳 — 여행 기록. 지도에 핀으로 표시하기 위해 위·경도를 함께 저장한다.
 */
@Entity
@Table(name = "travel_log")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TravelLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonIgnore
    private User user;

    @Column(nullable = false)
    private String title;      // 장소명 (예: 도톤보리)

    @Column(length = 60)
    private String country;    // 국가

    @Column(length = 60)
    private String city;       // 도시·지역

    // 지도 핀 좌표 (WGS84). 등록 시 지도 클릭/검색으로 채운다.
    @Column
    private Double lat;

    @Column
    private Double lng;

    // 여행 기간 (당일치기면 endDate == startDate 또는 endDate null)
    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    // 별점 1~5
    @Column
    private Integer rating;

    @Column(length = 2000)
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
