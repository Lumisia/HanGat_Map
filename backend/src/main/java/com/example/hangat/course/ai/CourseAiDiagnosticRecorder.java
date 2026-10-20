package com.example.hangat.course.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** No method accepts a Throwable or free-form diagnostic message. Not exposed by a controller. */
@Service
public class CourseAiDiagnosticRecorder {
    public enum CallKind { INITIAL, CORRECTION, FALLBACK }
    public enum Outcome { PENDING, CORRECTION_SUCCEEDED, FALLBACK_SUCCEEDED, FALLBACK_FAILED }
    private static final Logger log = LoggerFactory.getLogger(CourseAiDiagnosticRecorder.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public CourseAiDiagnosticRecorder(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(3);
    }

    public UUID record(UUID traceId, UUID jobId, CallKind kind, CourseAiDiagnostic diagnostic) {
        UUID id = UUID.randomUUID();
        try {
            transaction.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO course_ai_diagnostics
                (id, trace_id, job_id, occurred_at, call_kind, failure_type, phase, http_status,
                 google_status, google_reason, network_type, model, attempts, elapsed_ms, outcome, validation_code)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id.toString(), traceId.toString(), jobId == null ? null : jobId.toString(),
                    Timestamp.from(Instant.now()), kind.name(), diagnostic.failureType().name(),
                    diagnostic.phase().name(), diagnostic.httpStatus(), diagnostic.googleStatus(),
                    diagnostic.googleReason(), diagnostic.networkType(), diagnostic.model(),
                    diagnostic.attempts(), diagnostic.elapsedMs(), Outcome.PENDING.name(),
                    diagnostic.validationCode() == null ? null : diagnostic.validationCode().name()));
            log.warn("AI_PROVIDER_FAILURE traceId={} eventId={} type={} phase={} httpStatus={}",
                    traceId, id, diagnostic.failureType(), diagnostic.phase(), diagnostic.httpStatus());
            return id;
        } catch (RuntimeException ignored) {
            // Driver exception messages can contain SQL/connection information. Never log the exception.
            log.warn("AI_DIAGNOSTIC_WRITE_FAILED traceId={}", traceId);
            return null;
        }
    }

    public void complete(UUID id, Outcome outcome) {
        if (id == null) return;
        try {
            transaction.executeWithoutResult(status -> jdbc.update(
                    "UPDATE course_ai_diagnostics SET outcome = ? WHERE id = ?",
                    outcome.name(), id.toString()));
        } catch (RuntimeException ignored) {
            log.warn("AI_DIAGNOSTIC_UPDATE_FAILED eventId={}", id);
        }
    }

    /** Fixed, short retention. Failure is non-fatal and never prints a DB exception. */
    @Scheduled(cron = "0 15 4 * * *", zone = "Asia/Seoul")
    public void purgeExpired() {
        try {
            transaction.executeWithoutResult(status -> jdbc.update(
                    "DELETE FROM course_ai_diagnostics WHERE occurred_at < ?",
                    Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS))));
        } catch (RuntimeException ignored) {
            log.warn("AI_DIAGNOSTIC_RETENTION_FAILED");
        }
    }
}
