package com.example.hangat.course.ai;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiRetryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-09-03T00:00:00Z");

    @Test
    void retriesOnlyTheApprovedTransientStatusesAndNetworkFailures() {
        GeminiRetryPolicy policy = policy(0);

        assertThat(policy.isRetryableStatus(408)).isTrue();
        assertThat(policy.isRetryableStatus(429)).isTrue();
        assertThat(policy.isRetryableStatus(500)).isTrue();
        assertThat(policy.isRetryableStatus(502)).isTrue();
        assertThat(policy.isRetryableStatus(503)).isTrue();
        assertThat(policy.isRetryableStatus(504)).isTrue();
        assertThat(policy.isRetryableStatus(400)).isFalse();
        assertThat(policy.isRetryableStatus(401)).isFalse();
        assertThat(policy.isRetryableStatus(403)).isFalse();
        assertThat(policy.isRetryableStatus(404)).isFalse();

        assertThat(policy.isRetryableNetworkFailure("CONNECT_TIMEOUT")).isTrue();
        assertThat(policy.isRetryableNetworkFailure("READ_TIMEOUT")).isTrue();
        assertThat(policy.isRetryableNetworkFailure("CONNECT")).isTrue();
        assertThat(policy.isRetryableNetworkFailure("UNKNOWN_HOST")).isFalse();
        assertThat(policy.isRetryableNetworkFailure("IO")).isFalse();
    }

    @Test
    void capsGeneralAttemptsAtThreeAndReadTimeoutAttemptsAtTwo() {
        GeminiRetryPolicy policy = policy(0);

        assertThat(policy.canRetry(1, false)).isTrue();
        assertThat(policy.canRetry(2, false)).isTrue();
        assertThat(policy.canRetry(3, false)).isFalse();
        assertThat(policy.canRetry(1, true)).isTrue();
        assertThat(policy.canRetry(2, true)).isFalse();
    }

    @Test
    void appliesExponentialBackoffAndBoundedJitter() {
        GeminiRetryPolicy policy = policy(250);

        assertThat(policy.retryDelay(1, null)).isEqualTo(Duration.ofMillis(1_250));
        assertThat(policy.retryDelay(2, null)).isEqualTo(Duration.ofMillis(2_250));
    }

    @Test
    void prioritizesRetryAfterAndCapsItAtFiveSeconds() {
        GeminiRetryPolicy policy = policy(250);
        HttpHeaders seconds = new HttpHeaders();
        seconds.set(HttpHeaders.RETRY_AFTER, "20");
        HttpHeaders date = new HttpHeaders();
        date.set(HttpHeaders.RETRY_AFTER, ZonedDateTime.ofInstant(
                NOW.plusSeconds(3), ZoneOffset.UTC)
                .format(DateTimeFormatter.RFC_1123_DATE_TIME));

        assertThat(policy.retryDelay(1, seconds)).isEqualTo(Duration.ofSeconds(5));
        assertThat(policy.retryDelay(1, date)).isEqualTo(Duration.ofMillis(3_250));
    }

    @Test
    void fallsBackToBackoffForInvalidRetryAfter() {
        GeminiRetryPolicy policy = policy(0);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "not-a-date");

        assertThat(policy.retryDelay(1, headers)).isEqualTo(Duration.ofSeconds(1));
    }

    private GeminiRetryPolicy policy(long jitterMillis) {
        return new GeminiRetryPolicy(
                duration -> { },
                () -> jitterMillis,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
