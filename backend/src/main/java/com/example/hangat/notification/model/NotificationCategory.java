package com.example.hangat.notification.model;

import java.util.List;

/** 알림 내역의 분류. 새 알림 유형도 기타에서 빠짐없이 볼 수 있다. */
public enum NotificationCategory {
    ALL(List.of()),
    LOGIN(List.of("SECURITY_LOGIN")),
    COURSE(List.of("AI_COURSE_COMPLETED", "AI_COURSE_FAILED")),
    TRIP(List.of("FORECAST_CHANGE", "CONGESTION_WORSENED", "TRIP_SUMMARY", "REVIEW_REQUEST")),
    OTHER(List.of());

    private final List<String> types;
    NotificationCategory(List<String> types) { this.types = types; }

    public List<String> queryTypes() {
        return switch (this) {
            case ALL -> List.of(""); // 빈 IN 목록을 피한다. ALL 쿼리는 이 값에 의존하지 않는다.
            case OTHER -> java.util.stream.Stream.of(LOGIN, COURSE, TRIP)
                    .flatMap(category -> category.types.stream()).toList();
            default -> types;
        };
    }
}
