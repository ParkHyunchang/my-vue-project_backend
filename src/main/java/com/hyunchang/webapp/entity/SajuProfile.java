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
import java.time.LocalTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 사주 프로필 — 저장해둔 생년월일시 + 계산된 사주팔자 + 마지막 AI 해석 리포트 스냅샷. 여러 명(나, 가족 등)을 저장할 수 있다. */
@Entity
@Table(name = "saju_profile")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SajuProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonIgnore
    private User user;

    @Column(nullable = false, length = 40)
    private String label; // 예: 나, 아내

    // 계산에 사용된 양력 기준일. 음력 입력이면 KASI API로 환산된 양력 날짜를 저장한다.
    @Column(name = "birth_date", nullable = false)
    private LocalDate birthDate;

    @Column(name = "birth_time")
    private LocalTime birthTime; // timeUnknown=true 면 null

    @Column(name = "time_unknown", nullable = false)
    private boolean timeUnknown;

    // "SOLAR" | "LUNAR" — 사용자가 입력한 달력 종류. birthDate 는 어느 쪽이든 항상 환산된 양력 날짜다.
    @Column(name = "calendar_type", nullable = false, length = 10)
    private String calendarType = "SOLAR";

    // 아래 4개는 calendarType=LUNAR 일 때만 채워진다 (원본 음력 입력값 보존 — 수정 화면 재표시용).
    @Column(name = "lunar_year")
    private Integer lunarYear;

    @Column(name = "lunar_month")
    private Integer lunarMonth;

    @Column(name = "lunar_day")
    private Integer lunarDay;

    @Column(name = "leap_month", nullable = false)
    private boolean leapMonth;

    // 계산된 사주팔자(SajuResultDto) JSON 문자열. @JsonRawValue 로 응답 시 escape 없이 그대로 내보낸다.
    @Column(name = "palja_json", columnDefinition = "TEXT")
    @JsonRawValue
    private String paljaJson;

    // 마지막 AI 해석 리포트 스냅샷 (마크다운)
    @Column(name = "last_report_markdown", columnDefinition = "TEXT")
    private String lastReportMarkdown;

    @Column(name = "analyzed_at")
    private LocalDateTime analyzedAt;

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
