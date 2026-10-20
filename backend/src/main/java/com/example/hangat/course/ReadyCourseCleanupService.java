package com.example.hangat.course;

import com.example.hangat.course.model.entity.Course;
import com.example.hangat.course.model.enums.CourseStatus;
import com.example.hangat.course.model.enums.CourseType;
import com.example.hangat.course.repository.AsyncCourseJobRepository;
import com.example.hangat.course.repository.CourseItemCostRepository;
import com.example.hangat.course.repository.CourseItemRepository;
import com.example.hangat.course.repository.CourseRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 한 번에 제한된 수만 잠그고 연관 전용 데이터를 정리한 뒤 코스를 물리 삭제한다. */
@Service
public class ReadyCourseCleanupService {
    public static final int BATCH_SIZE = 100;
    private final CourseRepository courses;
    private final CourseItemRepository items;
    private final CourseItemCostRepository costs;
    private final AsyncCourseJobRepository jobs;
    private final CourseRetentionPolicy retention;

    public ReadyCourseCleanupService(CourseRepository courses, CourseItemRepository items,
            CourseItemCostRepository costs, AsyncCourseJobRepository jobs, CourseRetentionPolicy retention) {
        this.courses = courses;
        this.items = items;
        this.costs = costs;
        this.jobs = jobs;
        this.retention = retention;
    }

    @Transactional
    public int cleanupBatch() {
        int expired = 0;
        for (Long id : courses.findExpiredReadyIds(retention.cutoff(), PageRequest.of(0, BATCH_SIZE))) {
            Course course = courses.findByIdForClaim(id).orElse(null);
            if (course == null || course.getCourseType() != CourseType.USER
                    || course.getStatus() != CourseStatus.READY || !retention.isExpiredReady(course)
                    || jobs.hasActiveForCourse(id)) {
                continue;
            }
            // Same course row lock is used by claim/save. Whichever wins is rechecked here.
            costs.deleteByCourse(id);
            items.deleteByCourse(id);
            jobs.deleteTerminalArtifactsForCourse(id);
            courses.delete(course);
            expired++;
        }
        return expired;
    }
}
