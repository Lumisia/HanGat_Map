package com.example.hangat.course.ai;

import java.util.Set;

/** Closed diagnostic vocabulary. Never carry a prompt, response, URL, header or exception message. */
public record CourseAiDiagnostic(
        CourseAiFailureType failureType, Phase phase, Integer httpStatus,
        String googleStatus, String googleReason, String networkType,
        String model, int attempts, long elapsedMs, CourseAiValidationCode validationCode) {
    public CourseAiDiagnostic(CourseAiFailureType failureType, Phase phase, Integer httpStatus,
                              String googleStatus, String googleReason, String networkType,
                              String model, int attempts, long elapsedMs) {
        this(failureType, phase, httpStatus, googleStatus, googleReason, networkType, model,
                attempts, elapsedMs, null);
    }
    public enum Phase {
        CONFIGURATION, BUILD_REQUEST, REQUEST_SERIALIZATION, HTTP_EXCHANGE,
        RESPONSE_EXTRACTION, PARSE_ENVELOPE, PARSE_RESULT, VALIDATE_RESULT, UNKNOWN
    }
    private static final Set<String> STATUSES = Set.of("INVALID_ARGUMENT", "FAILED_PRECONDITION",
            "PERMISSION_DENIED", "UNAUTHENTICATED", "NOT_FOUND", "RESOURCE_EXHAUSTED", "CANCELLED",
            "INTERNAL", "UNAVAILABLE", "DEADLINE_EXCEEDED", "UNKNOWN", "UNIMPLEMENTED",
            "ABORTED", "OUT_OF_RANGE", "ALREADY_EXISTS", "DATA_LOSS");
    private static final Set<String> REASONS = Set.of("API_KEY_INVALID", "API_KEY_EXPIRED",
            "API_KEY_SERVICE_BLOCKED", "API_KEY_HTTP_REFERRER_BLOCKED", "API_KEY_IP_ADDRESS_BLOCKED",
            "API_KEY_ANDROID_APP_BLOCKED", "API_KEY_IOS_APP_BLOCKED", "API_KEY_NOT_FOUND",
            "SERVICE_DISABLED", "BILLING_DISABLED", "BILLING_NOT_ACTIVE", "CONSUMER_INVALID",
            "CONSUMER_SUSPENDED", "ACCESS_TOKEN_EXPIRED", "ACCESS_TOKEN_SCOPE_INSUFFICIENT",
            "RATE_LIMIT_EXCEEDED", "QUOTA_EXCEEDED", "RESOURCE_EXHAUSTED", "MODEL_NOT_FOUND",
            "IAM_PERMISSION_DENIED", "BACKEND_ERROR", "SERVICE_UNAVAILABLE", "INVALID_JSON_PAYLOAD");
    private static final Set<String> NETWORKS = Set.of("DNS", "CONNECT_TIMEOUT", "READ_TIMEOUT", "CONNECT", "IO");

    public CourseAiDiagnostic {
        failureType = failureType == null ? CourseAiFailureType.PROVIDER_ERROR : failureType;
        phase = phase == null ? Phase.UNKNOWN : phase;
        httpStatus = httpStatus != null && httpStatus >= 100 && httpStatus <= 599 ? httpStatus : null;
        googleStatus = allowed(googleStatus, STATUSES);
        googleReason = allowed(googleReason, REASONS);
        networkType = allowed(networkType, NETWORKS);
        model = safeModel(model);
        attempts = Math.max(0, Math.min(attempts, 3));
        elapsedMs = Math.max(0, elapsedMs);
    }

    public static String googleStatus(String value) { return allowed(value, STATUSES); }
    public static String googleReason(String value) { return allowed(value, REASONS); }

    public static String safeModel(String value) {
        return value != null && value.matches("gemini-\\d{1,2}(?:\\.\\d{1,2})?-(?:flash|pro)(?:-lite)?(?:-preview(?:-\\d{2}-\\d{2})?)?")
                ? value : "OTHER";
    }

    private static String allowed(String value, Set<String> values) {
        return value == null ? null : values.contains(value) ? value : "OTHER";
    }

    static CourseAiDiagnostic unknown(CourseAiException failure) {
        return new CourseAiDiagnostic(failure.getFailureType(),
                failure instanceof CourseAiValidationException ? Phase.VALIDATE_RESULT : Phase.UNKNOWN,
                null, null, null, null, null, 0, 0,
                failure instanceof CourseAiValidationException validation ? validation.getCode() : null);
    }

    CourseAiDiagnostic withValidationCode(CourseAiValidationCode code) {
        return new CourseAiDiagnostic(failureType, phase, httpStatus, googleStatus, googleReason,
                networkType, model, attempts, elapsedMs, code);
    }
}
