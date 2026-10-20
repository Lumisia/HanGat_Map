package com.example.hangat.course.service;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.common.model.BaseResponseStatus;
import com.example.hangat.course.model.CourseSummaryResponse;
import com.example.hangat.course.model.entity.Course;
import com.example.hangat.course.model.enums.CourseStatus;
import com.example.hangat.course.repository.CourseItemRepository;
import com.example.hangat.course.repository.CourseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 저장 코스 관리 (MY_001) - 이름 변경·삭제.
 *
 * <p>둘 다 <b>소유자 본인만</b> 할 수 있다. 삭제는 물리 삭제가 아니라 논리 삭제라
 * 되돌릴 여지를 남기고, 지운 코스를 또 지워도 오류가 아니다(멱등).
 */
@Service
@Transactional
public class CourseCommandService {

    private final CourseRepository courseRepository;
    private final CourseItemRepository itemRepository;

    public CourseCommandService(CourseRepository courseRepository,
                                CourseItemRepository itemRepository) {
        this.courseRepository = courseRepository;
        this.itemRepository = itemRepository;
    }

    public CourseSummaryResponse rename(Long courseId, String title, Long authUserId) {
        Course course = ownedByMe(courseId, authUserId);
        // 삭제는 멱등이라 주인이 삭제된 코스에 접근할 수 있지만, 이름 변경은 되살리기가 아니다
        if (course.getStatus() == CourseStatus.DELETED) {
            throw new BaseException(BaseResponseStatus.COURSE_NOT_FOUND, courseId);
        }
        course.rename(title.trim());
        return CourseSummaryResponse.of(course, itemRepository.findItemsWithPlace(courseId));
    }

    /**
     * 이미 지운 코스를 또 지워도 성공으로 답한다 - 화면이 두 번 눌러도 오류를 보지 않게.
     *
     * <p><b>멱등 단축은 소유자 확인 뒤에 온다.</b> 앞에 두면 남의 삭제된 코스에 성공(2000)이 나가
     * "이 id는 존재했고 지워졌다"가 새어 나간다(같은 id가 GET에서는 3301인데 DELETE만 2000).
     */
    public void delete(Long courseId, Long authUserId) {
        Course course = ownedByMe(courseId, authUserId);
        course.softDelete();
    }

    /**
     * 삭제된 코스는 "없는 코스"로 답한다 - 존재 여부까지 숨긴다. 단 <b>주인에게는</b> 삭제된 코스도
     * 돌려준다(삭제의 멱등 재호출 때문). 즉 남에게는 3301, 주인에게는 정상 진행이다.
     * 소유자 없는 임시 코스는 이름 변경·삭제 대상이 아니다(저장하지 않은 코스라 지울 것도 없다).
     */
    private Course ownedByMe(Long courseId, Long authUserId) {
        // 공유 생성/중지와 같은 부모 행 잠금: 삭제 직후 공유가 재활성화되는 경합을 막는다.
        Course course = courseRepository.findByIdForClaim(courseId)
                .orElseThrow(() -> new BaseException(BaseResponseStatus.COURSE_NOT_FOUND, courseId));
        if (!isOwner(course, authUserId)) {
            // 남의 눈에는 삭제된 코스와 없는 코스가 같아야 한다
            throw course.getStatus() == CourseStatus.DELETED
                    ? new BaseException(BaseResponseStatus.COURSE_NOT_FOUND, courseId)
                    : new BaseException(BaseResponseStatus.COURSE_FORBIDDEN, courseId);
        }
        return course;
    }

    private boolean isOwner(Course course, Long authUserId) {
        return course.getUser() != null
                && authUserId != null
                && course.getUser().getId().equals(authUserId);
    }
}
