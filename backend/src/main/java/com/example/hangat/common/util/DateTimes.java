package com.example.hangat.common.util;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 시각 유틸 - UTC로 통일하려고 만듦.
 * LocalDateTime.now()는 서버 시간대를 따라서 KST면 UTC가 아니고 Hibernate도 안 바꿔줌.
 * 엔티티마다 각자 만들면 한 곳만 빠져도 만료 판정이 9시간 어긋남.
 */
public class DateTimes {
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private DateTimes() {
    }

    public static LocalDateTime nowUtc() {
        return nowUtc(Clock.systemUTC());
    }

    public static LocalDateTime nowUtc(Clock clock) {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /** User calendar date, independent of the host/JVM timezone. */
    public static LocalDate todayKst() {
        return todayKst(Clock.systemUTC());
    }

    public static LocalDate todayKst(Clock clock) {
        return LocalDate.ofInstant(clock.instant(), KST);
    }
}
