package com.example.hangat.course;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.common.model.BaseResponseStatus;
import com.example.hangat.course.model.AccommodationDto;
import com.example.hangat.course.model.CourseAccommodationUpdateRequest;
import com.example.hangat.course.model.CourseAccommodationSearchRequest;
import com.example.hangat.course.model.entity.Course;
import com.example.hangat.course.repository.CourseItemRepository;
import com.example.hangat.course.model.enums.CourseStatus;
import com.example.hangat.course.model.enums.CourseType;
import com.example.hangat.course.repository.CourseRepository;
import com.example.hangat.map.model.entity.PlaceSourceMapping;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CourseAccommodationService {

    private final CourseRepository courseRepository;
    private final CourseClaimTokenService claimTokenService;
    private final CoursePlaceResolver placeResolver;
    private final CourseItemRepository itemRepository;
    private final KakaoAccommodationProvider kakaoAccommodationProvider;

    public CourseAccommodationService(
            CourseRepository courseRepository,
            CourseClaimTokenService claimTokenService,
            CoursePlaceResolver placeResolver,
            CourseItemRepository itemRepository,
            KakaoAccommodationProvider kakaoAccommodationProvider
    ) {
        this.courseRepository = courseRepository;
        this.claimTokenService = claimTokenService;
        this.placeResolver = placeResolver;
        this.itemRepository = itemRepository;
        this.kakaoAccommodationProvider = kakaoAccommodationProvider;
    }

    @Transactional(readOnly = true)
    public List<AccommodationDto> recommend(
            Long courseId, CourseAccommodationSearchRequest request, Long authUserId
    ) {
        Course course = courseRepository.findByIdForClaim(courseId)
                .orElseThrow(() -> new BaseException(BaseResponseStatus.COURSE_NOT_FOUND));
        authorize(course, request.claimToken(), authUserId);
        return kakaoAccommodationProvider.recommend(coursePlaces(courseId)).stream()
                .map(verified -> AccommodationDto.fromKakao(verified.place(), verified.region()))
                .toList();
    }

    @Transactional
    public AccommodationDto update(
            Long courseId,
            CourseAccommodationUpdateRequest request,
            Long authUserId
    ) {
        Course course = courseRepository.findByIdForClaim(courseId)
                .orElseThrow(() -> new BaseException(BaseResponseStatus.COURSE_NOT_FOUND));
        authorize(course, request.claimToken(), authUserId);

        var accommodation = request.accommodation();
        var verified = kakaoAccommodationProvider.verify(
                coursePlaces(courseId), accommodation.getSourceCode(), accommodation.getSourcePlaceId());
        PlaceSourceMapping mapping = placeResolver.resolveVerifiedAccommodation(verified);
        course.changeAccommodation(mapping);
        return AccommodationDto.from(mapping);
    }

    private List<com.example.hangat.map.model.entity.Place> coursePlaces(Long courseId) {
        return itemRepository.findItemsWithPlace(courseId).stream()
                .map(item -> item.getPlace())
                .toList();
    }

    private void authorize(Course course, String claimToken, Long authUserId) {
        if (course.getStatus() == CourseStatus.DELETED) {
            throw new BaseException(BaseResponseStatus.COURSE_NOT_FOUND);
        }
        if (course.getCourseType() == CourseType.SAMPLE) {
            throw new BaseException(BaseResponseStatus.COURSE_FORBIDDEN);
        }
        if (course.getStatus() == CourseStatus.READY && course.getUser() == null) {
            claimTokenService.validate(claimToken, course.getId());
            return;
        }
        if (course.getStatus() == CourseStatus.SAVED && course.getUser() != null) {
            if (authUserId == null) {
                throw new BaseException(BaseResponseStatus.LOGIN_REQUIRED);
            }
            if (!course.getUser().getId().equals(authUserId)) {
                throw new BaseException(BaseResponseStatus.COURSE_FORBIDDEN);
            }
            return;
        }
        throw new BaseException(BaseResponseStatus.COURSE_NOT_FOUND);
    }
}
