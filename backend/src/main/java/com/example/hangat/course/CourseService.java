package com.example.hangat.course;

import com.example.hangat.course.ai.CourseAiInputDto;
import com.example.hangat.course.ai.CourseAiGenerationService;
import com.example.hangat.course.ai.CourseAiResultDto;
import com.example.hangat.course.facts.CourseGenerationFacts;
import com.example.hangat.course.model.AccommodationDto;
import com.example.hangat.course.model.CongestionDto;
import com.example.hangat.course.model.CourseCandidateDto;
import com.example.hangat.course.model.CourseRegionDto;
import com.example.hangat.course.model.CourseRequestDto;
import com.example.hangat.course.model.CourseResponseDto;
import com.example.hangat.course.model.CourseStyleDto;
import com.example.hangat.course.model.PlacePreferenceDto;
import com.example.hangat.course.model.PreferenceType;
import com.example.hangat.course.model.TourPlaceDto;
import com.example.hangat.course.model.Transport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class CourseService {

    private final TourApiService tourApiService;
    private final CongestionApiService congestionApiService;
    private final CourseCandidateShortlistService courseCandidateShortlistService;
    private final CourseAiPreparationService courseAiPreparationService;
    private final CourseAiGenerationService courseAiGenerationService;
    private final CoursePersistenceService coursePersistenceService;
    private final CourseBudgetService courseBudgetService;
    private final CourseResponseAssembler courseResponseAssembler;

    public CourseResponseDto createCourse(CourseRequestDto request) {
        PreparedCourse prepared = prepareCourse(request);
        CourseAiResultDto result = courseAiGenerationService.generate(prepared.input());
        result = CourseVisitOrderOptimizer.optimize(request, prepared.facts(), result);
        CoursePersistenceResult persistence = coursePersistenceService.persist(
                request,
                prepared.facts(),
                result,
                prepared.metadata());
        CourseBudgetCalculation budget = courseBudgetService.calculateAndCache(
                persistence.course().getId());
        return courseResponseAssembler.assemble(
                prepared.facts(), result, persistence,
                request.getAccommodation(), budget);
    }

    CourseAiInputDto prepareAiInput(CourseRequestDto request) {
        return prepareCourse(request).input();
    }

    private PreparedCourse prepareCourse(CourseRequestDto request) {
        LocalDate startDate = request.getStartDate();
        LocalDate endDate = request.getEndDate();
        Integer people = request.getPeople();
        Integer budgetTotal = request.getBudgetTotal();
        Transport transport = request.getTransport();
        List<CourseRegionDto> courseRegions = request.getCourseRegions();
        List<CourseStyleDto> courseStyles = request.getCourseStyles();
        List<PlacePreferenceDto> coursePlacePreferences = request.getCoursePlacePreferences();
        AccommodationDto accommodation = request.getAccommodation();

        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("여행 날짜는 필수입니다.");
        }

        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("종료일은 시작일보다 빠를 수 없습니다.");
        }

        if (people == null || people <= 0) {
            throw new IllegalArgumentException("인원은 1명 이상이어야 합니다.");
        }

        if (budgetTotal == null || budgetTotal <= 0) {
            throw new IllegalArgumentException("예산은 0원보다 커야 합니다.");
        }

        if (transport == null) {
            throw new IllegalArgumentException("이동수단은 필수입니다.");
        }

        if (courseStyles == null || courseStyles.isEmpty()) {
            throw new IllegalArgumentException("여행 스타일은 1개 이상 선택해야 합니다.");
        }

        if (courseRegions == null) {
            throw new IllegalArgumentException("권역 정보가 필요합니다.");
        }

        validatePlacePreferences(coursePlacePreferences, startDate, endDate);

        List<TourPlaceDto> tourPlaces = tourApiService.getTourPlaces();

        if (tourPlaces.isEmpty()) {
            throw new IllegalArgumentException("조회된 관광지가 없습니다.");
        }

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        List<CourseCandidateDto> courseCandidates = new ArrayList<>();

        List<CourseCandidateShortlistService.ShortlistedPlace> shortlistedPlaces =
                courseCandidateShortlistService.select(request, tourPlaces);

        if (shortlistedPlaces.isEmpty() && (coursePlacePreferences == null
                || coursePlacePreferences.stream().noneMatch(p -> p != null
                && p.getPreferenceType() == PreferenceType.WANT))) {
            throw new IllegalArgumentException("선택한 권역에서 일정을 구성할 수 있는 장소가 없습니다.");
        }

        for (CourseCandidateShortlistService.ShortlistedPlace shortlisted : shortlistedPlaces) {
            TourPlaceDto place = shortlisted.place();
            PreferenceType preferenceType = shortlisted.preferenceType();
            List<String> confirmedStyleHints = shortlisted.confirmedStyleHints();
            String signguCd;

            if (place.getAddress() != null && place.getAddress().contains("제주시")) {
                signguCd = "50110";
            } else if (place.getAddress() != null && place.getAddress().contains("서귀포시")) {
                signguCd = "50130";
            } else {
                courseCandidates.add(new CourseCandidateDto(
                        place,
                        Collections.emptyList(),
                        preferenceType,
                        confirmedStyleHints
                ));
                continue;
            }

            List<CongestionDto> congestionData = congestionApiService.getCongestionData(
                    signguCd,
                    place.getTitle()
            );

            if (congestionData.isEmpty()) {
                courseCandidates.add(new CourseCandidateDto(
                        place,
                        Collections.emptyList(),
                        preferenceType,
                        confirmedStyleHints
                ));
                continue;
            }

            List<CongestionDto> filteredCongestionData = congestionData.stream()
                    .filter(congestion -> {
                        LocalDate congestionDate = LocalDate.parse(
                                congestion.getBaseYmd(),
                                formatter
                        );

                        return !congestionDate.isBefore(startDate)
                                && !congestionDate.isAfter(endDate);
                    })
                    .toList();

            courseCandidates.add(new CourseCandidateDto(
                    place,
                    filteredCongestionData,
                    preferenceType,
                    confirmedStyleHints
            ));
        }

        CourseAiPreparationService.PreparedGeneration generation =
                courseAiPreparationService.prepareGeneration(request, courseCandidates);
        return new PreparedCourse(
                generation.input(),
                generation.facts(),
                generation.metadata());
    }

    private record PreparedCourse(
            CourseAiInputDto input,
            CourseGenerationFacts facts,
            CourseGenerationMetadata metadata
    ) {
    }

    private void validatePlacePreferences(
            List<PlacePreferenceDto> preferences,
            LocalDate startDate,
            LocalDate endDate
    ) {
        if (preferences == null || preferences.isEmpty()) {
            return;
        }

        for (int i = 0; i < preferences.size(); i++) {
            PlacePreferenceDto first = preferences.get(i);

            if (first == null || first.getPreferenceType() == null) {
                continue;
            }

            validateFixedSchedule(first, startDate, endDate);

            for (int j = i + 1; j < preferences.size(); j++) {
                PlacePreferenceDto second = preferences.get(j);

                if (second == null || second.getPreferenceType() == null) {
                    continue;
                }

                if (isSamePreferredPlace(first, second)) {
                    if (first.getPreferenceType() == second.getPreferenceType()) {
                        throw new IllegalArgumentException(
                                "동일한 장소를 중복 지정할 수 없습니다."
                        );
                    }
                    throw new IllegalArgumentException(
                            "동일한 장소를 WANT와 AVOID에 동시에 지정할 수 없습니다."
                    );
                }
            }
        }
    }

    private void validateFixedSchedule(
            PlacePreferenceDto preference,
            LocalDate startDate,
            LocalDate endDate
    ) {
        LocalDate fixedDate = preference.getFixedDate();

        if (preference.getPreferenceType() == PreferenceType.AVOID
                && (fixedDate != null || preference.getFixedTime() != null)) {
            throw new IllegalArgumentException(
                    "AVOID 장소에는 고정 날짜나 시간을 지정할 수 없습니다."
            );
        }

        if (preference.getFixedTime() != null && fixedDate == null) {
            throw new IllegalArgumentException(
                    "고정 시간은 고정 날짜와 함께 지정해야 합니다."
            );
        }

        if (fixedDate != null
                && (fixedDate.isBefore(startDate) || fixedDate.isAfter(endDate))) {
            throw new IllegalArgumentException("고정 방문일은 여행기간 내여야 합니다.");
        }
    }

    private boolean isSamePreferredPlace(
            PlacePreferenceDto first,
            PlacePreferenceDto second
    ) {
        if (first.getPlaceId() != null && second.getPlaceId() != null) {
            return first.getPlaceId().equals(second.getPlaceId());
        }

        if (hasSourceIdentity(first) && hasSourceIdentity(second)) {
            return first.getSourceCode().trim().equalsIgnoreCase(second.getSourceCode().trim())
                    && first.getSourcePlaceId().trim().equals(second.getSourcePlaceId().trim());
        }

        String firstName = normalizePlaceName(first.getPlaceName());
        String secondName = normalizePlaceName(second.getPlaceName());

        return !firstName.isEmpty() && firstName.equals(secondName);
    }

    private boolean hasSourceIdentity(PlacePreferenceDto preference) {
        return preference.getSourceCode() != null
                && !preference.getSourceCode().isBlank()
                && preference.getSourcePlaceId() != null
                && !preference.getSourcePlaceId().isBlank();
    }

    private String normalizePlaceName(String value) {
        return value == null
                ? ""
                : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}
