package com.example.hangat.course.ai;

import org.springframework.http.HttpHeaders;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

final class GeminiRetryPolicy {

    static final int MAX_ATTEMPTS = 3;
    static final int MAX_READ_TIMEOUT_ATTEMPTS = 2;
    static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(5);

    private static final Set<Integer> RETRYABLE_STATUSES =
            Set.of(408, 429, 500, 502, 503, 504);
    private static final Duration FIRST_BACKOFF = Duration.ofSeconds(1);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(2);
    private static final long MAX_JITTER_MILLIS = 250;

    private final Sleeper sleeper;
    private final LongSupplier jitterMillis;
    private final Clock clock;

    static GeminiRetryPolicy production() {
        return new GeminiRetryPolicy(
                duration -> Thread.sleep(duration.toMillis()),
                () -> ThreadLocalRandom.current().nextLong(MAX_JITTER_MILLIS + 1),
                Clock.systemUTC());
    }

    GeminiRetryPolicy(Sleeper sleeper, LongSupplier jitterMillis, Clock clock) {
        this.sleeper = sleeper;
        this.jitterMillis = jitterMillis;
        this.clock = clock;
    }

    boolean isRetryableStatus(int status) {
        return RETRYABLE_STATUSES.contains(status);
    }

    boolean isRetryableNetworkFailure(String failureType) {
        return "CONNECT_TIMEOUT".equals(failureType)
                || "READ_TIMEOUT".equals(failureType)
                || "CONNECT".equals(failureType);
    }

    boolean isReadTimeout(String failureType) {
        return "READ_TIMEOUT".equals(failureType);
    }

    boolean canRetry(int failedAttempt, boolean readTimeoutOccurred) {
        int maximumAttempts = readTimeoutOccurred
                ? MAX_READ_TIMEOUT_ATTEMPTS
                : MAX_ATTEMPTS;
        return failedAttempt < maximumAttempts;
    }

    Duration retryDelay(int failedAttempt, HttpHeaders responseHeaders) {
        Duration retryAfter = parseRetryAfter(responseHeaders);
        Duration base = retryAfter != null
                ? retryAfter
                : exponentialBackoff(failedAttempt);
        Duration withJitter = base.plusMillis(safeJitterMillis());
        return retryAfter == null ? withJitter : min(withJitter, MAX_RETRY_AFTER);
    }

    void sleep(Duration delay) throws InterruptedException {
        sleeper.sleep(delay);
    }

    private Duration exponentialBackoff(int failedAttempt) {
        long multiplier = 1L << Math.max(0, failedAttempt - 1);
        return min(FIRST_BACKOFF.multipliedBy(multiplier), MAX_BACKOFF);
    }

    private Duration parseRetryAfter(HttpHeaders headers) {
        if (headers == null) {
            return null;
        }
        String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long seconds = Long.parseLong(value.trim());
            return min(Duration.ofSeconds(Math.max(0, seconds)), MAX_RETRY_AFTER);
        } catch (NumberFormatException ignored) {
            try {
                Instant retryAt = ZonedDateTime.parse(
                        value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                Duration delay = Duration.between(clock.instant(), retryAt);
                return min(delay.isNegative() ? Duration.ZERO : delay, MAX_RETRY_AFTER);
            } catch (DateTimeParseException invalidHeader) {
                return null;
            }
        }
    }

    private long safeJitterMillis() {
        return Math.max(0, Math.min(MAX_JITTER_MILLIS, jitterMillis.getAsLong()));
    }

    private Duration min(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }
}
