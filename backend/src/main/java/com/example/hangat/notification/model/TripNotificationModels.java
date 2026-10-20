package com.example.hangat.notification.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.LocalDate;

/**
 * 여행 확정 / 알림 수신 설정의 요청·응답 모델.
 * 서비스 내부 조회 결과도 이쪽 모델로 구분한다.
 */
public final class TripNotificationModels {

    /**
     * 회원별 수신 설정.
     * version은 현재 화면이 마지막으로 읽은 설정 버전이다.
     */
    public record Preferences(
            @NotNull Boolean aiCourse,
            @NotNull Boolean forecastChange,
            @NotNull Boolean congestion,
            @NotNull Boolean tripSummary,
            @NotNull Boolean reviewRequest,
            @NotNull @PositiveOrZero Long version
    ) {
    }

    /**
     * 현재 확정한 여행.
     * courseId가 null이면 확정한 여행이 없다.
     */
    public record Trip(
            Long courseId,
            String courseTitle,
            LocalDate startDate,
            LocalDate endDate,
            long version,
            boolean scheduleChanged
    ) {
    }

    /**
     * 여행 확정 / 다른 여행으로 교체 요청.
     * 사용자 ID는 요청에서 받지 않고 로그인 정보에서 가져온다.
     */
    public record ConfirmRequest(
            @NotNull @Positive Long courseId,
            @NotNull @PositiveOrZero Long expectedVersion
    ) {
    }

    /**
     * DB에서 함께 조회하는 수신 설정과 여행 확정 상태.
     * API 응답에는 필요한 부분만 사용한다.
     */
    public record State(
            Preferences preferences,
            Trip trip
    ) {
    }
}
