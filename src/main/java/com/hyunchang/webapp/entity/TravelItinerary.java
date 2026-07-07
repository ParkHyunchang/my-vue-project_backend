package com.hyunchang.webapp.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonRawValue;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 예정 일정 — 조만간 갈 여행의 실제 일정. AI 플래너 결과를 저장한 뒤 사용자가 다듬는다. itinerary 컬럼에는 일자별 일정(JSON 배열 문자열)을 그대로
 * 저장한다: [{ "day":1, "theme":"...", "items":[{ "time":"오전", "type":"관광", "place":"...", "desc":"..."
 * }] }]
 */
@Entity
@Table(name = "travel_itinerary")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TravelItinerary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonIgnore
    private User user;

    @Column(nullable = false)
    private String title; // 일정 제목 (예: 오사카 3박4일)

    @Column(length = 120)
    private String destination; // 목적지

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column private Integer days;

    // 일자별 일정 JSON 배열 문자열. @JsonRawValue 로 응답 시 escape 없이 그대로 JSON 으로 내보낸다.
    @Column(columnDefinition = "TEXT")
    @JsonRawValue
    private String itinerary;

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
