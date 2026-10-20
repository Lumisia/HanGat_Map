package com.example.hangat.course;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 10분마다 최대 100건. 실제 만료 반영은 경계 뒤 최대 한 주기와 backlog만큼 늦을 수 있다. */
@Component
@ConditionalOnProperty(name = "course.ready-cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class ReadyCourseCleanupScheduler {
    private final ReadyCourseCleanupService cleanup;

    public ReadyCourseCleanupScheduler(ReadyCourseCleanupService cleanup) {
        this.cleanup = cleanup;
    }

    @Scheduled(fixedDelayString = "${course.ready-cleanup.interval-ms:600000}",
            initialDelayString = "${course.ready-cleanup.initial-delay-ms:600000}")
    public void cleanup() {
        cleanup.cleanupBatch();
    }
}
