package com.example.hangat.course.ai;

import java.util.UUID;

/** Worker-local correlation only; no request data. Always close on the same worker thread. */
public final class CourseAiDiagnosticContext implements AutoCloseable {
    private static final ThreadLocal<UUID> JOB = new ThreadLocal<>();
    private final UUID previous;

    private CourseAiDiagnosticContext(UUID jobId) {
        previous = JOB.get();
        JOB.set(jobId);
    }

    public static CourseAiDiagnosticContext forJob(UUID jobId) { return new CourseAiDiagnosticContext(jobId); }
    static UUID jobId() { return JOB.get(); }

    @Override public void close() {
        if (previous == null) JOB.remove(); else JOB.set(previous);
    }
}
