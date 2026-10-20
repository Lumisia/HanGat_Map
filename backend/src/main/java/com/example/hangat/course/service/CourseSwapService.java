package com.example.hangat.course.service;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.common.model.BaseResponseStatus;
import com.example.hangat.course.model.CourseSwapResponse;
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
import com.example.hangat.map.repository.PlaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 과밀 스팟 원클릭 스왑 (담당: 정동현) - #과밀지역우회의 히어로 모먼트.
 *
 * <p>코스 일정 한 칸을 대안 장소로 <b>제자리 교체</b>하고, 그 여파를 한 트랜잭션에서 정리한다:
 * ① 교체 흔적·예보 스냅숏({@link CourseItem#replaceWith}) ② 앞뒤 이동 거리·시간 재계산
 * ③ 코스 평균 집중률 재계산. 순서(day_no, position)는 그대로라 화면 번호 마커가 흔들리지 않는다.
 *
 * <p><b>평균 집중률은 "지금 예보" 기준으로 다시 계산한다.</b> 저장 시점 스냅숏은
 * "그때 뭘 보고 골랐는지"의 기록이고, 카드에 보이는 평균은 현재 예보를 반영해야 한다.
 * 예보가 없는 일정은 평균에서 빼고, 하나도 없으면 null로 둔다 - 0으로 채우면 '정보 없음'이
 * '한산'으로 둔갑한다.
 */
@Service
public class CourseSwapService {

    private final CourseRepository courseRepository;
    private final CourseItemRepository itemRepository;
    private final PlaceRepository placeRepository;
    private final CongestionService congestionService;
    private final CourseTravelCalculator travelCalculator;
    private final com.example.hangat.course.CourseRetentionPolicy retentionPolicy;
    private final com.example.hangat.course.CourseBudgetService budgets;

    public CourseSwapService(CourseRepository courseRepository,
                             CourseItemRepository itemRepository,
                             PlaceRepository placeRepository,
                             CongestionService congestionService,
                             CourseTravelCalculator travelCalculator,
                             com.example.hangat.course.CourseRetentionPolicy retentionPolicy,
                             com.example.hangat.course.CourseBudgetService budgets) {
        this.courseRepository = courseRepository;
        this.itemRepository = itemRepository;
        this.placeRepository = placeRepository;
        this.congestionService = congestionService;
        this.travelCalculator = travelCalculator;
        this.retentionPolicy = retentionPolicy;
        this.budgets = budgets;
    }

    /**
     * @param authUserId 인증된 회원 id. 비로그인이면 null - 소유자 없는 임시 코스만 바꿀 수 있다
     *                   (코스 생성 자체가 비로그인 허용이라 생성 직후 스왑도 열려 있어야 한다)
     */
    @Transactional
    public CourseSwapResponse swap(Long courseId, Long itemId, Long newPlaceId, Long authUserId) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new BaseException(BaseResponseStatus.COURSE_NOT_FOUND, courseId));
        retentionPolicy.requireAvailable(course);
        if (course.getStatus() == CourseStatus.DELETED) {
            throw new BaseException(BaseResponseStatus.COURSE_NOT_FOUND, courseId);
        }
        if (course.getStatus() != CourseStatus.READY && course.getStatus() != CourseStatus.SAVED) {
            throw new BaseException(BaseResponseStatus.COURSE_NOT_CLAIMABLE, course.getStatus().name());
        }
        // ⚠️ 샘플 코스는 소유자가 없지만 "임자 없는 코스"가 아니라 <b>모두의 것</b>이다.
        // 아래 소유자 검사만으로는 통과해 버려, 메인 추천 카드를 누구나 영구히 바꿀 수 있게 된다
        // (배치가 같은 출발일을 재생성하지 않으므로 복구도 안 된다). 먼저 막는다.
        if (course.getCourseType() == CourseType.SAMPLE) {
            throw new BaseException(BaseResponseStatus.COURSE_FORBIDDEN, courseId);
        }
        // 소유자가 있는 코스는 본인만. 소유자 없는 임시 코스는 URL을 아는 사람이 바꿀 수 있다(생성 정책과 동일)
        if (course.getUser() != null
                && (authUserId == null || !course.getUser().getId().equals(authUserId))) {
            throw new BaseException(BaseResponseStatus.COURSE_FORBIDDEN, courseId);
        }

        List<CourseItem> items = itemRepository.findItemsWithPlace(courseId);
        CourseItem target = items.stream()
                .filter(item -> item.getId().equals(itemId))
                .findFirst()
                .orElseThrow(() -> new BaseException(BaseResponseStatus.COURSE_ITEM_NOT_FOUND, itemId));

        Place newPlace = placeRepository.findById(newPlaceId)
                .orElseThrow(() -> new BaseException(BaseResponseStatus.PLACE_NOT_FOUND, newPlaceId));
        boolean duplicated = items.stream()
                .anyMatch(item -> !item.getId().equals(itemId)
                        && item.getPlace().getId().equals(newPlaceId));
        if (duplicated) {
            throw new BaseException(BaseResponseStatus.COURSE_PLACE_DUPLICATED, newPlace.getName());
        }

        String replacedName = target.getPlace().getName();
        CongestionForecast forecast = congestionService
                .forecastOf(newPlaceId, target.getVisitDate())
                .orElse(null);

        // 근거 코드는 명세서 목록(CONGESTION/STYLE/GOOD_PRICE/HIDDEN_GEM/ROUTE) 안에서 고른다 -
        // 대안 후보가 애초에 '혼잡 미만'으로 걸러진 것들이라 CONGESTION이 맞다
        target.replaceWith(newPlace, forecast, "CONGESTION", "직접 바꾼 곳이에요");

        List<CourseItem> updated = recalculateTravel(items, target);
        Map<LocalDate, Map<Long, Double>> ratesByDate = new HashMap<>();
        BigDecimal average = recalculateAverageRate(items, ratesByDate);
        course.updateAggregates(course.getEstimatedCostMin(), course.getEstimatedCostMax(), average);
        budgets.clearItemCosts(courseId, itemId);
        var budget = budgets.calculateAndCache(courseId);

        return new CourseSwapResponse(
                course.getId(),
                average,
                average == null ? null : CongestionLevel.from(average),
                average == null ? null : CongestionLevel.from(average).label(),
                updated.stream()
                        .map(item -> CourseSwapResponse.SwappedItem.of(item, rateOf(item, ratesByDate),
                                budget.itemCostsByItemId().getOrDefault(item.getId(), List.of()).stream()
                                        .map(com.example.hangat.course.CourseResponseAssembler::toCostDto).toList()))
                        .toList(),
                replacedName + " 대신 " + newPlace.getName() + "으로 바꾸고 동선을 다시 계산했어요",
                com.example.hangat.course.CourseResponseAssembler.toBudgetSummary(budget));
    }

    /**
     * 교체된 일정과 <b>그 다음 일정</b>의 이동 정보를 다시 계산한다.
     * 장소가 바뀌면 들어오는 이동(이 일정)과 나가는 이동(다음 일정의 inbound)이 함께 변한다.
     *
     * @return 값이 바뀐 일정들 - 화면이 갱신해야 할 목록
     */
    private List<CourseItem> recalculateTravel(List<CourseItem> items, CourseItem target) {
        List<CourseItem> changed = new ArrayList<>();
        changed.add(target);

        int index = items.indexOf(target);
        CourseItem previous = index > 0 ? items.get(index - 1) : null;
        // 하루의 첫 일정은 전날 마지막 장소에서 이어지지 않는다 - 숙소·이동이 끼므로 '이동 없음'으로 둔다
        if (previous != null && previous.getDayNo().equals(target.getDayNo())) {
            apply(target, previous);
        } else {
            target.updateInbound(null, null);
        }

        CourseItem next = index + 1 < items.size() ? items.get(index + 1) : null;
        if (next != null && next.getDayNo().equals(target.getDayNo())) {
            apply(next, target);
            changed.add(next);
        }
        return changed;
    }

    private void apply(CourseItem item, CourseItem previous) {
        CourseTravelCalculator.Travel travel =
                travelCalculator.between(previous.getPlace(), item.getPlace());
        item.updateInbound(travel.distanceM(), travel.minutes());
    }

    /** 예보가 있는 일정만 평균 낸다. 하나도 없으면 null - 없는 값을 0으로 만들지 않는다. */
    private BigDecimal recalculateAverageRate(List<CourseItem> items,
                                              Map<LocalDate, Map<Long, Double>> ratesByDate) {
        double sum = 0;
        int count = 0;
        for (CourseItem item : items) {
            Double rate = rateOf(item, ratesByDate);
            if (rate != null) {
                sum += rate;
                count++;
            }
        }
        return count == 0 ? null
                : BigDecimal.valueOf(sum / count).setScale(2, RoundingMode.HALF_UP);
    }

    /** 날짜별 예보 맵을 캐시해 재사용한다 - 코스는 최대 3일이라 조회가 3번을 넘지 않는다. */
    private Double rateOf(CourseItem item, Map<LocalDate, Map<Long, Double>> ratesByDate) {
        return ratesByDate
                .computeIfAbsent(item.getVisitDate(), congestionService::ratesFor)
                .get(item.getPlace().getId());
    }
}
