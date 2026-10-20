package com.example.hangat.common.util;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.assertThat;

class DateTimesTest {
    @Test void koreaMidnightDoesNotChangeUtcStorageClock() {
        var before = Clock.fixed(Instant.parse("2026-09-06T14:59:59Z"), ZoneOffset.UTC);
        var after = Clock.offset(before, Duration.ofSeconds(1));
        assertThat(DateTimes.todayKst(before)).isEqualTo(LocalDate.of(2026, 9, 6));
        assertThat(DateTimes.todayKst(after)).isEqualTo(LocalDate.of(2026, 9, 7));
        assertThat(DateTimes.nowUtc(after)).isEqualTo(LocalDateTime.of(2026, 9, 6, 15, 0));
        assertThat(DateTimes.todayKst(after.withZone(ZoneId.of("America/Los_Angeles"))))
                .isEqualTo(DateTimes.todayKst(after));
    }

    @Test void threeDayTripKeepsDateOnlyIdentity() {
        LocalDate start = LocalDate.parse("2026-09-06");
        assertThat(start.datesUntil(start.plusDays(3)).map(LocalDate::toString))
                .containsExactly("2026-09-06", "2026-09-07", "2026-09-08");
    }
}
