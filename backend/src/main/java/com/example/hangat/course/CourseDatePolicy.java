package com.example.hangat.course;

import com.example.hangat.common.util.DateTimes;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import com.example.hangat.common.exception.BaseException;
import com.example.hangat.common.model.BaseResponseStatus;
import java.util.Map;

/** User-facing congestion forecast window. Dates are Jeju/KST calendar values. */
@Component
public class CourseDatePolicy {
    public static final int FORECAST_WINDOW_DAYS = 30;
    private final Clock clock;
    private final boolean enabled;

    public CourseDatePolicy() {
        this(Clock.systemUTC(), true);
    }

    CourseDatePolicy(Clock clock) {
        this(clock, true);
    }

    private CourseDatePolicy(Clock clock, boolean enabled) {
        this.clock = clock;
        this.enabled = enabled;
    }

    static CourseDatePolicy legacyTestCompatibility() { return new CourseDatePolicy(Clock.systemUTC(), false); }

    public void validate(LocalDate start, LocalDate end) {
        if (!enabled || start == null || end == null) return;
        LocalDate today = DateTimes.todayKst(clock);
        LocalDate last = today.plusDays(FORECAST_WINDOW_DAYS - 1L);
        if (start.isBefore(today) || end.isAfter(last)) {
            throw new BaseException(BaseResponseStatus.COURSE_INVALID_CONDITION,
                    Map.of("date", "여행 날짜는 오늘부터 30일 이내로 선택해 주세요. 범위 안에서도 아직 적재되지 않은 예보는 정보 없음으로 표시됩니다."));
        }
    }
}
