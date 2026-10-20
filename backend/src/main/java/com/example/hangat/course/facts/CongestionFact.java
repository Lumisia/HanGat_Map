package com.example.hangat.course.facts;

import com.example.hangat.map.model.enums.CongestionLevel;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

public record CongestionFact(
        Long congestionForecastId,
        LocalDate date,
        BigDecimal rate,
        CongestionLevel level,
        String sourceCode
) {
    private static final BigDecimal MIN_RATE = BigDecimal.ZERO;
    private static final BigDecimal MAX_RATE = BigDecimal.valueOf(100);

    public CongestionFact {
        Objects.requireNonNull(date, "혼잡도 기준일은 필수입니다.");
        Objects.requireNonNull(rate, "혼잡도 rate는 필수입니다.");
        Objects.requireNonNull(level, "혼잡도 level은 필수입니다.");
        if (rate.compareTo(MIN_RATE) < 0 || rate.compareTo(MAX_RATE) > 0) {
            throw new IllegalArgumentException("혼잡도 rate는 0부터 100 사이여야 합니다.");
        }
        if (sourceCode != null && sourceCode.isBlank()) {
            throw new IllegalArgumentException("혼잡도 sourceCode는 공백일 수 없습니다.");
        }
    }
}
