package com.example.hangat.course;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.common.model.BaseResponseStatus;
import com.example.hangat.common.util.DateTimes;
import com.example.hangat.course.model.entity.Course;
import com.example.hangat.course.model.enums.CourseStatus;
import com.example.hangat.course.model.enums.CourseType;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

/** 저장하지 않은 USER 코스는 생성 시각부터 정확히 2시간 동안만 유지한다. */
@Component
public class CourseRetentionPolicy {
    public static final Duration READY_RETENTION = Duration.ofHours(2);
    private final Clock clock;

    public CourseRetentionPolicy() {
        this(Clock.systemUTC());
    }

    CourseRetentionPolicy(Clock clock) {
        this.clock = clock;
    }

    public LocalDateTime cutoff() {
        return DateTimes.nowUtc(clock).minus(READY_RETENTION);
    }

    public boolean isExpiredReady(Course course) {
        return course != null
                && course.getCourseType() == CourseType.USER
                && course.getStatus() == CourseStatus.READY
                && course.getCreatedAt() != null
                && !course.getCreatedAt().isAfter(cutoff());
    }

    public void requireAvailable(Course course) {
        if (course != null && (course.getStatus() == CourseStatus.EXPIRED || isExpiredReady(course))) {
            throw new BaseException(BaseResponseStatus.COURSE_EXPIRED);
        }
    }
}
