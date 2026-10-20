package com.example.hangat.course.share;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.common.model.BaseResponseStatus;
import com.example.hangat.course.model.entity.Course;
import com.example.hangat.course.model.enums.CourseStatus;
import com.example.hangat.course.model.enums.CourseType;
import com.example.hangat.course.repository.CourseRepository;
import com.example.hangat.course.service.CourseQueryService;
import com.example.hangat.user.model.UserStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.security.SecureRandom;
import java.util.Base64;

@Service
@Transactional(readOnly = true)
public class CourseShareService {
    private final CourseRepository courses;
    private final CourseShareRepository shares;
    private final CourseQueryService query;
    private final SecureRandom random = new SecureRandom();

    public CourseShareService(CourseRepository courses, CourseShareRepository shares, CourseQueryService query) {
        this.courses = courses; this.shares = shares; this.query = query;
    }
    public record State(boolean active, String token) {
        static State off() { return new State(false, null); }
        static State of(CourseShare share) { return share.isActive() ? new State(true, share.getToken()) : off(); }
    }
    public State status(Long courseId, Long userId) {
        requireOwner(courses.findById(courseId).orElseThrow(this::missingCourse), userId);
        return shares.findById(courseId).map(State::of).orElseGet(State::off);
    }
    @Transactional
    public State create(Long courseId, Long userId) {
        // 존재하지 않는 공유 행을 잠그는 대신 항상 존재하는 부모 행으로 최초 생성까지 직렬화한다.
        requireOwner(courses.findByIdForClaim(courseId).orElseThrow(this::missingCourse), userId);
        CourseShare share = shares.findById(courseId).orElseGet(() -> new CourseShare(courseId));
        if (!share.isActive()) {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            share.activate(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
            shares.save(share);
        }
        return State.of(share);
    }
    @Transactional
    public State revoke(Long courseId, Long userId) {
        requireOwner(courses.findByIdForClaim(courseId).orElseThrow(this::missingCourse), userId);
        shares.findById(courseId).ifPresent(CourseShare::revoke);
        return State.off();
    }
    public SharedCourseResponse read(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw unavailable();
        CourseShare share = shares.findByTokenAndActiveTrue(token).orElseThrow(this::unavailable);
        Course course = courses.findById(share.getCourseId()).orElseThrow(this::unavailable);
        if (!shareable(course)) throw unavailable();
        // 이 호출 전 토큰 및 현재 상태를 검증한다. 외부에는 allowlist DTO만 반환한다.
        return SharedCourseResponse.from(query.detail(course.getId(), course.getUser().getId()));
    }
    private void requireOwner(Course course, Long userId) {
        if (userId == null) throw new BaseException(BaseResponseStatus.LOGIN_REQUIRED);
        if (course.getStatus() == CourseStatus.DELETED) throw missingCourse();
        if (course.getUser() == null || !course.getUser().getId().equals(userId)) {
            throw new BaseException(BaseResponseStatus.COURSE_FORBIDDEN);
        }
        if (!shareable(course)) throw new BaseException(BaseResponseStatus.COURSE_FORBIDDEN);
    }
    private boolean shareable(Course course) {
        return course.getStatus() == CourseStatus.SAVED && course.getCourseType() == CourseType.USER
                && course.getUser() != null && course.getUser().getStatus() == UserStatus.ACTIVE;
    }
    private BaseException missingCourse() { return new BaseException(BaseResponseStatus.COURSE_NOT_FOUND); }
    private BaseException unavailable() { return new BaseException(BaseResponseStatus.COURSE_SHARE_UNAVAILABLE); }
}
