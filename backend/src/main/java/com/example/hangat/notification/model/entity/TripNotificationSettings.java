package com.example.hangat.notification.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 회원별 알림 수신 설정 / 현재 확정한 여행.
 * 수신 설정과 여행 확정은 서로 다른 버전으로 변경 충돌을 검사한다.
 * 변경은 Repository에서 행 잠금을 확보한 상태에서만 수행한다.
 */
@Entity
@Table(name = "user_notification_settings")
@Getter
@DynamicUpdate
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TripNotificationSettings {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "ai_course", nullable = false, columnDefinition = "boolean")
    private boolean aiCourse;
    @Column(name = "forecast_change", nullable = false, columnDefinition = "boolean")
    private boolean forecastChange;
    @Column(name = "congestion", nullable = false, columnDefinition = "boolean")
    private boolean congestion;
    @Column(name = "trip_summary", nullable = false, columnDefinition = "boolean")
    private boolean tripSummary;
    @Column(name = "review_request", nullable = false, columnDefinition = "boolean")
    private boolean reviewRequest;

    // 두 버전을 독립적으로 유지하므로 @Version 대신 행 잠금과 명시적 비교를 사용한다.
    @Column(name = "preferences_version", nullable = false)
    private long preferencesVersion;
    @Column(name = "trip_version", nullable = false)
    private long tripVersion;

    @Column(name = "trip_course_id")
    private Long tripCourseId;
    @Column(name = "trip_title", length = 100)
    private String tripTitle;
    @Column(name = "trip_start_date")
    private LocalDate tripStartDate;
    @Column(name = "trip_end_date")
    private LocalDate tripEndDate;
    @Column(name = "trip_confirmed_at", columnDefinition = "datetime(6)")
    private LocalDateTime tripConfirmedAt;
    @Column(name = "updated_at", nullable = false, columnDefinition = "datetime(6)")
    private LocalDateTime updatedAt;

    /** 수신 항목 변경 시 여행 확정 버전은 건드리지 않는다. */
    public void changePreferences(boolean aiCourse,
                                  boolean forecastChange, boolean congestion,
                                  boolean tripSummary, boolean reviewRequest,
                                  LocalDateTime now) {
        this.aiCourse = aiCourse;
        this.forecastChange = forecastChange;
        this.congestion = congestion;
        this.tripSummary = tripSummary;
        this.reviewRequest = reviewRequest;
        preferencesVersion++;
        updatedAt = now;
    }

    /** 확정 당시 날짜를 보관해 이후 코스 일정 변경을 감지한다. */
    public void confirm(Long courseId, String title, LocalDate startDate,
                        LocalDate endDate, LocalDateTime now) {
        tripCourseId = courseId;
        tripTitle = title;
        tripStartDate = startDate;
        tripEndDate = endDate;
        tripConfirmedAt = now;
        tripVersion++;
        updatedAt = now;
    }

    /** 취소해도 버전을 되돌리지 않아 이전 작업의 늦은 알림을 차단한다. */
    public void cancel(LocalDateTime now) {
        tripCourseId = null;
        tripTitle = null;
        tripStartDate = null;
        tripEndDate = null;
        tripConfirmedAt = null;
        tripVersion++;
        updatedAt = now;
    }
}
