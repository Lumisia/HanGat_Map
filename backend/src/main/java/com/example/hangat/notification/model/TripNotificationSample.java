package com.example.hangat.notification.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 알림 비교에 필요한 값만 담는 모델.
 * issuedAt은 UTC, date는 한국 기준 방문 날짜다.
 */
public record TripNotificationSample(
        String kind,
        LocalDate date,
        String label,
        String value,
        LocalDateTime issuedAt
) {
}