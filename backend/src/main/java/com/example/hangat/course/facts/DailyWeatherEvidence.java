package com.example.hangat.course.facts;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Stored daily forecast provenance, never a place-specific hourly observation. */
@com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DailyWeatherEvidence(String sourceCode, String regionCode, String spatialScope,
        String granularity, LocalDateTime issuedAtUtc, BigDecimal tempMin, BigDecimal tempMax) { }
