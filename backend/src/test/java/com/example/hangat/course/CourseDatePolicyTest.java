package com.example.hangat.course;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.example.hangat.common.exception.BaseException;
import com.example.hangat.common.model.BaseResponseStatus;

class CourseDatePolicyTest {
    private final CourseDatePolicy policy = new CourseDatePolicy(
            Clock.fixed(Instant.parse("2026-09-10T15:00:00Z"), ZoneOffset.UTC));

    @Test void acceptsKstTodayThroughInclusiveDayThirty() {
        assertThatCode(() -> policy.validate(LocalDate.of(2026, 9, 11), LocalDate.of(2026, 10, 10)))
                .doesNotThrowAnyException();
    }

    @Test void rejectsPastAndOutsideForecastWindowSeparatelyFromMissingForecastData() {
        assertThatThrownBy(() -> policy.validate(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 11)))
                .isInstanceOfSatisfying(BaseException.class, error -> {
                    org.assertj.core.api.Assertions.assertThat(error.getStatus())
                            .isEqualTo(BaseResponseStatus.COURSE_INVALID_CONDITION);
                    org.assertj.core.api.Assertions.assertThat(error.getResult().toString()).contains("30일");
                });
        assertThatThrownBy(() -> policy.validate(LocalDate.of(2026, 9, 11), LocalDate.of(2026, 10, 11)))
                .isInstanceOfSatisfying(BaseException.class, error ->
                        org.assertj.core.api.Assertions.assertThat(error.getResult().toString())
                                .contains("적재되지 않은 예보"));
    }
}
