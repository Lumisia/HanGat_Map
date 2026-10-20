package com.example.hangat.course.facts;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

public record WeatherFact(
        Long weatherForecastId,
        LocalDate forecastDate,
        LocalTime forecastTime,
        BigDecimal temperature,
        Integer precipitationProbability,
        String precipitationTypeCode,
        String skyConditionCode,
        BigDecimal windSpeed,
        Integer humidity,
        DailyWeatherEvidence dailyEvidence
) {
    public WeatherFact(Long id, LocalDate date, LocalTime time, BigDecimal temperature,
            Integer probability, String precipitation, String sky, BigDecimal wind, Integer humidity) {
        this(id, date, time, temperature, probability, precipitation, sky, wind, humidity, null);
    }
    public WeatherFact {
        Objects.requireNonNull(forecastDate, "예보 대상일은 필수입니다.");
        if (dailyEvidence == null) Objects.requireNonNull(forecastTime, "예보 대상시각은 필수입니다.");
        requirePercentage(precipitationProbability, "강수확률");
        requirePercentage(humidity, "습도");
        if (windSpeed != null && windSpeed.signum() < 0) {
            throw new IllegalArgumentException("풍속은 음수일 수 없습니다.");
        }
    }

    private static void requirePercentage(Integer value, String label) {
        if (value != null && (value < 0 || value > 100)) {
            throw new IllegalArgumentException(label + "은 0부터 100 사이여야 합니다.");
        }
    }
}
