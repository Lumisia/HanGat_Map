package com.example.hangat.course;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.common.model.BaseResponseStatus;
import com.example.hangat.course.model.CourseClaimRequest;
import com.example.hangat.course.model.CourseClaimResponse;
import com.example.hangat.course.model.entity.Course;
import com.example.hangat.course.model.enums.CourseStatus;
import com.example.hangat.course.model.enums.CourseType;
import com.example.hangat.course.repository.CourseRepository;
import com.example.hangat.user.model.User;
import com.example.hangat.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CourseClaimService {

    private final UserRepository userRepository;
    private final CourseRepository courseRepository;
    private final CourseClaimTokenService tokenService;
    private final CourseRetentionPolicy retentionPolicy;

    @Autowired
    public CourseClaimService(UserRepository userRepository, CourseRepository courseRepository,
            CourseClaimTokenService tokenService, CourseRetentionPolicy retentionPolicy) {
        this.userRepository = userRepository;
        this.courseRepository = courseRepository;
        this.tokenService = tokenService;
        this.retentionPolicy = retentionPolicy;
    }

    /** 기존 단위 테스트 호환용. 운영 주입은 위 생성자를 사용한다. */
    CourseClaimService(UserRepository userRepository, CourseRepository courseRepository,
            CourseClaimTokenService tokenService) {
        this(userRepository, courseRepository, tokenService, new CourseRetentionPolicy());
    }

    @Transactional
    public CourseClaimTokenService.ClaimProof renew(Long courseId, String token) {
        return renew(courseId, token, null);
    }

    @Transactional
    public CourseClaimTokenService.ClaimProof renew(Long courseId, String token, Long userId) {
        // Validate possession first; a numeric id alone must not mint a proof.
        tokenService.validate(token, courseId);

        Course course = courseRepository.findByIdForClaim(courseId)
                .orElseThrow(() ->
                        new BaseException(BaseResponseStatus.COURSE_NOT_FOUND));

        retentionPolicy.requireAvailable(course);

        if (course.getStatus() != CourseStatus.READY
                || course.getCourseType() == CourseType.SAMPLE
                || (course.getUser() != null && !course.getUser().getId().equals(userId))) {
            throw new BaseException(BaseResponseStatus.COURSE_NOT_CLAIMABLE);
        }

        return tokenService.renew(token, courseId);
    }

    @Transactional
    public CourseClaimResponse claim(Long courseId, Long userId, CourseClaimRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BaseException(BaseResponseStatus.USER_NOT_FOUND));
        Course course = courseRepository.findByIdForClaim(courseId)
                .orElseThrow(() -> new BaseException(BaseResponseStatus.COURSE_NOT_FOUND));

        retentionPolicy.requireAvailable(course);

        if (course.getStatus() != CourseStatus.READY
                || course.getCourseType() == CourseType.SAMPLE) {
            throw new BaseException(BaseResponseStatus.COURSE_NOT_CLAIMABLE);
        }

        if (course.getUser() != null
                && !course.getUser().getId().equals(userId)) {
            throw new BaseException(BaseResponseStatus.COURSE_NOT_CLAIMABLE);
        }

        tokenService.validate(request.claimToken(), courseId);
        course.markSaved(user, request.title().trim());

        return new CourseClaimResponse(
                course.getId(), course.getStatus(), course.getTitle(), course.getSavedAt());
    }
}
