package com.example.hangat.course;

import com.example.hangat.map.model.enums.CongestionLevel;

import java.math.BigDecimal;
import java.util.Optional;

public final class CongestionLevelResolver {

    private static final BigDecimal MIN_RATE = BigDecimal.ZERO;
    private static final BigDecimal MAX_RATE = new BigDecimal("100");

    private CongestionLevelResolver() {
    }

    public static Optional<CongestionLevel> resolve(String cnctrRate) {
        if (cnctrRate == null || cnctrRate.isBlank()) {
            return Optional.empty();
        }

        final BigDecimal rate;

        try {
            rate = new BigDecimal(cnctrRate.trim());
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }

        if (rate.compareTo(MIN_RATE) < 0 || rate.compareTo(MAX_RATE) > 0) {
            return Optional.empty();
        }

        return Optional.of(CongestionLevel.from(rate));
    }
}
