package com.example.hangat.course.service;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.common.model.BaseResponseStatus;
import com.example.hangat.common.model.PageResponse;
import com.example.hangat.course.model.CourseDetailResponse;
import com.example.hangat.course.model.AccommodationDto;
import com.example.hangat.course.model.CourseDuration;
import com.example.hangat.course.model.CourseSummaryResponse;
import com.example.hangat.course.model.entity.Course;
import com.example.hangat.course.model.entity.CourseItem;
import com.example.hangat.course.model.enums.CourseStatus;
import com.example.hangat.course.model.enums.CourseType;
import com.example.hangat.course.repository.CourseItemRepository;
import com.example.hangat.course.repository.CourseRepository;
import com.example.hangat.domain.congestion.CongestionService;
import com.example.hangat.map.model.entity.CongestionForecast;
import com.example.hangat.map.model.entity.Place;
import com.example.hangat.map.model.enums.CongestionLevel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 코스 조회 (담당: 정동현) - 메인 추천 카드·저장 코스 목록의 진입점.
 *
 * <p><b>공개 범위</b>: 소유자가 없는 코스(비회원 임시·메인 샘플)는 누구나 열 수 있다 -
 * 비로그인으로 만든 코스를 로그인 전에 다시 봐야 하고, 메인 샘플은 공개 자산이기 때문이다.
 * 소유자가 있는 저장 코스는 본인만 볼 수 있다.
 */
@Service
@Transactional(readOnly = true)
public class CourseQueryService {

    private final CourseRepository courseRepository;
    private final CourseItemRepository itemRepository;
    private final CongestionService congestionService;

    public CourseQueryService(CourseRepository courseRepository,
                              CourseItemRepository itemRepository,
                              CongestionService congestionService) {
        this.courseRepository = courseRepository;
        this.itemRepository = itemRepository;
        this.congestionService = congestionService;
    }

    /**
     * 저장 코스 목록(MY_001) - 내가 저장한 코스만, 최근 저장 순.
     * 논리 삭제라 상태를 항상 SAVED로 못 박는다 - 지운 코스가 목록에 새면 안 된다.
     *
     * <p><b>정렬은 서비스가 정한다</b> - 요청의 sort를 그대로 넘기면 ① 호출자가 안 주면 DB 순서가
     * 나오고 ② 임의 문자열이 오면 처리되지 않는 500이 난다. 같은 시각에 저장된 코스가 있어도
     * 순서가 흔들리지 않게 id를 2차 기준으로 둔다.
     */
    public PageResponse<CourseSummaryResponse> savedCourses(Long userId, Pageable pageable) {
        Pageable sorted = PageRequest.of(
                pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Order.desc("savedAt"), Sort.Order.desc("id")));
        Page<Course> page = courseRepository.findByUserIdAndStatus(
                userId, CourseStatus.SAVED, sorted);
        Map<Long, List<CourseItem>> itemsByCourse = itemsOf(page.getContent());
        return PageResponse.from(page.map(course -> CourseSummaryResponse.of(
                course, itemsByCourse.getOrDefault(course.getId(), List.of()))));
    }

    /** 카드마다 조회하면 페이지 한 장에 N번을 친다 - 한 번에 읽어 코스별로 나눈다. */
    private Map<Long, List<CourseItem>> itemsOf(List<Course> courses) {
        if (courses.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = courses.stream().map(Course::getId).toList();
        return itemRepository.findItemsOfCourses(ids).stream()
                .collect(Collectors.groupingBy(item -> item.getCourse().getId()));
    }

    /** @param authUserId 인증된 회원 id. 비로그인이면 null */
    public CourseDetailResponse detail(Long courseId, Long authUserId) {
        Course course = readable(courseId, authUserId);
        List<CourseItem> items = itemRepository.findItemsWithPlace(courseId);

        Map<LocalDate, Map<Long, Double>> ratesByDate = new HashMap<>();
        List<CourseDetailResponse.DayDto> days = groupByDay(items, ratesByDate);

        // 헤더 배지는 아래 일정들과 같은 기준(지금 예보)이어야 한다 - 저장된 캐시를 그대로 쓰면
        // "헤더는 혼잡인데 모든 일정은 여유"인 화면이 나온다
        BigDecimal current = currentAverage(days);
        CourseDuration duration = CourseDuration.between(course.getStartDate(), course.getEndDate());
        boolean mine = isOwner(course, authUserId);

        return new CourseDetailResponse(
                course.getId(),
                course.getCourseType(),
                course.getStatus(),
                course.getTitle(),
                course.getStartDate(),
                course.getEndDate(),
                duration.days(),
                duration.text(),
                course.getPeople(),
                course.getBudgetTotal(),
                course.getTransport(),
                course.getEstimatedCostMin(),
                course.getEstimatedCostMax(),
                current,
                current == null ? null : CongestionLevel.from(current),
                current == null ? null : CongestionLevel.from(current).label(),
                course.getAverageCongestionRate(),
                isSwappable(course, mine),
                mine && course.getStatus() == CourseStatus.SAVED,
                AccommodationDto.from(course.getAccommodationSourceMapping()),
                days);
    }

    /** 예보가 있는 일정만 평균 낸다 - 없는 값을 0으로 만들지 않는다(하나도 없으면 null). */
    private BigDecimal currentAverage(List<CourseDetailResponse.DayDto> days) {
        double sum = 0;
        int count = 0;
        for (CourseDetailResponse.DayDto day : days) {
            for (CourseDetailResponse.ItemDto item : day.items()) {
                if (item.congestionRate() != null) {
                    sum += item.congestionRate();
                    count++;
                }
            }
        }
        return count == 0 ? null
                : BigDecimal.valueOf(sum / count).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 조회 권한 확인. 삭제된 코스는 "없는 코스"로 답한다 - 존재 여부까지 숨긴다.
     */
    private Course readable(Long courseId, Long authUserId) {
        Course course = courseRepository.findByIdWithAccommodation(courseId)
                .orElseThrow(() -> new BaseException(BaseResponseStatus.COURSE_NOT_FOUND, courseId));
        if (course.getStatus() == CourseStatus.DELETED) {
            throw new BaseException(BaseResponseStatus.COURSE_NOT_FOUND, courseId);
        }
        if (course.getUser() != null
                && (authUserId == null || !course.getUser().getId().equals(authUserId))) {
            throw new BaseException(BaseResponseStatus.COURSE_FORBIDDEN, courseId);
        }
        return course;
    }

    /**
     * 스왑 가능 여부 - {@code CourseSwapService}의 권한 규칙과 같아야 한다.
     * 소유자 없는 임시 코스는 누구나(생성이 비로그인 허용이라), 저장 코스는 본인만,
     * 샘플은 공유 자산이라 아무도 못 바꾼다.
     */
    private boolean isSwappable(Course course, boolean mine) {
        if (course.getCourseType() == CourseType.SAMPLE) {
            return false;
        }
        return course.getUser() == null || mine;
    }

    private boolean isOwner(Course course, Long authUserId) {
        return course.getUser() != null
                && authUserId != null
                && course.getUser().getId().equals(authUserId);
    }

    /** 일차별로 묶는다 - 화면이 "1일차/2일차" 섹션으로 그린다. items는 이미 (일차, 순서) 정렬. */
    private List<CourseDetailResponse.DayDto> groupByDay(
            List<CourseItem> items, Map<LocalDate, Map<Long, Double>> ratesByDate) {
        List<CourseDetailResponse.DayDto> days = new ArrayList<>();
        int currentDay = -1;
        List<CourseDetailResponse.ItemDto> bucket = null;
        LocalDate bucketDate = null;

        for (CourseItem item : items) {
            if (item.getDayNo() != currentDay) {
                if (bucket != null) {
                    days.add(new CourseDetailResponse.DayDto(currentDay, bucketDate, bucket));
                }
                currentDay = item.getDayNo();
                bucketDate = item.getVisitDate();
                bucket = new ArrayList<>();
            }
            bucket.add(toItem(item, ratesByDate));
        }
        if (bucket != null) {
            days.add(new CourseDetailResponse.DayDto(currentDay, bucketDate, bucket));
        }
        return days;
    }

    private CourseDetailResponse.ItemDto toItem(CourseItem item,
                                                Map<LocalDate, Map<Long, Double>> ratesByDate) {
        Place place = item.getPlace();
        Double rate = ratesByDate
                .computeIfAbsent(item.getVisitDate(), congestionService::ratesFor)
                .get(place.getId());
        CongestionLevel level = rate == null ? null : CongestionLevel.from(BigDecimal.valueOf(rate));

        CongestionForecast planned = item.getPlannedCongestionForecast();
        Double plannedRate = planned == null ? null : planned.getRate().doubleValue();
        CongestionLevel plannedLevel = planned == null ? null : planned.getLevel();
        return new CourseDetailResponse.ItemDto(
                item.getId(),
                place.getId(),
                place.getName(),
                place.getPrimaryCategory().getName(),
                place.getRegion().getName(),
                place.getImageUrl(),
                place.getLatitude() == null ? null : place.getLatitude().doubleValue(),
                place.getLongitude() == null ? null : place.getLongitude().doubleValue(),
                item.getDayNo(),
                item.getPosition(),
                item.getVisitDate(),
                item.getStartTime(),
                item.getEndTime(),
                item.getItemSource(),
                item.getInboundDistanceM(),
                item.getInboundTravelMinutes() == null
                        ? null : item.getInboundTravelMinutes().intValue(),
                rate,
                level,
                level == null ? null : level.label(),
                plannedRate,
                plannedLevel,
                plannedLevel == null ? null : plannedLevel.label(),
                item.getRecommendationReasonCode(),
                item.getRecommendationReason(),
                item.getReplacedFromPlace() == null ? null : item.getReplacedFromPlace().getId(),
                item.getReplacedFromPlace() == null ? null : item.getReplacedFromPlace().getName());
    }
}
