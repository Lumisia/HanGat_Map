package com.example.hangat.course.share;

import com.example.hangat.common.util.DateTimes;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/** 코스당 하나의 공유 상태. 접근 증표인 token은 로그/toString에 포함하지 않는다. */
@Entity
@Table(name = "course_shares", uniqueConstraints = @UniqueConstraint(name = "uk_course_share_token", columnNames = "token"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CourseShare {
    @Id @Column(name = "course_id")
    private Long courseId;
    @Column(length = 43)
    private String token;
    @Column(nullable = false)
    private boolean active;
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    CourseShare(Long courseId) { this.courseId = courseId; }
    void activate(String token) {
        this.token = token;
        this.active = true;
        this.updatedAt = DateTimes.nowUtc();
    }
    void revoke() {
        this.active = false;
        this.token = null;
        this.updatedAt = DateTimes.nowUtc();
    }
}
