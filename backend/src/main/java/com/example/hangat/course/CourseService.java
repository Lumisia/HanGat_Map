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
@lombok.extern.slf4j.Slf4j
@RequiredArgsConstructor(onConstructor_ = @org.springframework.beans.factory.annotation.Autowired)
public class CourseService {

    private final TourApiService tourApiService;
    private final CongestionApiService congestionApiService;
    private final CourseCandidateShortlistService courseCandidateShortlistService;
    private final CourseAiPreparationService courseAiPreparationService;
    private final CourseAiGenerationService courseAiGenerationService;
    private final CoursePersistenceService coursePersistenceService;
    private final CourseBudgetService courseBudgetService;
    private final CourseResponseAssembler courseResponseAssembler;
    private final java.util.Optional<CourseDbCandidateService> dbCandidateService;
    private final CourseDatePolicy courseDatePolicy;
    private java.util.Optional<CourseAccommodationService> accommodationService = java.util.Optional.empty();

    // batch에서는 API 전용 숙소 서비스를 제외한다. API 실행 시에는 기존대로 주입한다.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setAccommodationService(CourseAccommodationService accommodationService) {
        this.accommodationService = java.util.Optional.of(accommodationService);
    }

    public CourseService(TourApiService tourApiService, CongestionApiService congestionApiService,
            CourseCandidateShortlistService courseCandidateShortlistService, CourseAiPreparationService courseAiPreparationService,
            CourseAiGenerationService courseAiGenerationService, CoursePersistenceService coursePersistenceService,
            CourseBudgetService courseBudgetService, CourseResponseAssembler courseResponseAssembler) {
        this(tourApiService, congestionApiService, courseCandidateShortlistService, courseAiPreparationService,
                courseAiGenerationService, coursePersistenceService, courseBudgetService, courseResponseAssembler,
                java.util.Optional.empty(), CourseDatePolicy.legacyTestCompatibility());
    }

    CourseService(TourApiService tourApiService, CongestionApiService congestionApiService,
            CourseCandidateShortlistService courseCandidateShortlistService, CourseAiPreparationService courseAiPreparationService,
            CourseAiGenerationService courseAiGenerationService, CoursePersistenceService coursePersistenceService,
            CourseBudgetService courseBudgetService, CourseResponseAssembler courseResponseAssembler,
            java.util.Optional<CourseDbCandidateService> dbCandidateService) {
        this(tourApiService, congestionApiService, courseCandidateShortlistService, courseAiPreparationService,
                courseAiGenerationService, coursePersistenceService, courseBudgetService, courseResponseAssembler,
                dbCandidateService, CourseDatePolicy.legacyTestCompatibility());
    }

    /**
     * 비회원의 기존 동기 생성 경로.
     */
    public CourseResponseDto createCourse(CourseRequestDto request) {
        ComputedCourse computed = computeCourse(request);
        if (request.getAccommodation() == null) {
            return persistComputedCourse(request, computed);
        }
        CourseAccommodationService accommodations = accommodationService.orElseThrow(
                () -> new IllegalStateException("숙소 검증 서비스를 사용할 수 없습니다."));
        var verified = accommodations.verifyGeneratedAccommodation(request.getAccommodation(), computed);
        return persistComputedCourse(request, computed, verified);
    }
    /**
     * 외부 조회 / AI 호출 / 방문 순서 최적화.
     * 이 단계 전체를 DB 트랜잭션으로 감싸지 않는다.
     */
    public ComputedCourse computeCourse(CourseRequestDto request) {
        checkCancelled();
        PreparedCourse prepared = prepareCourse(request);

        checkCancelled();

        CourseAiResultDto result =
                courseAiGenerationService.generate(prepared.input());

        result = CourseVisitOrderOptimizer.optimize(
                request,
                prepared.facts(),
                result
        );

        checkCancelled();

        return new ComputedCourse(
                prepared.facts(),
                result,
                prepared.metadata()
        );
    }

    /**
     * 생성 결과 저장과 응답 구성.
     *
     * 비동기 작업에서는 호출자가 연 트랜잭션 안에서 실행해
     * 코스 저장 / 작업 성공 / 알림 기록이 함께 확정되게 한다.
     */
    public CourseResponseDto persistComputedCourse(
            CourseRequestDto request,
            ComputedCourse computed
    ) {
        return persistComputedCourse(request, computed, null);
    }

    private CourseResponseDto persistComputedCourse(
            CourseRequestDto request,
            ComputedCourse computed,
            KakaoAccommodationProvider.VerifiedAccommodation accommodation
    ) {
        CoursePersistenceResult persistence = accommodation == null
                ? coursePersistenceService.persist(
                request,
                computed.facts(),
                computed.result(),
                computed.metadata()
                )
                : coursePersistenceService.persist(
                request,
                computed.facts(),
                computed.result(),
                computed.metadata(),
                accommodation
                );

        CourseBudgetCalculation budget =
                courseBudgetService.calculateAndCache(
                        persistence.course().getId()
                );

        return courseResponseAssembler.assemble(
                computed.facts(),
                computed.result(),
                persistence,
                accommodation == null
                        ? request.getAccommodation()
                        : AccommodationDto.fromKakao(accommodation.place(), accommodation.region()),
                budget
        );
    }

    public record ComputedCourse(
            CourseGenerationFacts facts,
            CourseAiResultDto result,
            CourseGenerationMetadata metadata
    ) {
    }

    CourseAiInputDto prepareAiInput(CourseRequestDto request) {
        return prepareCourse(request).input();
    }

    private PreparedCourse prepareCourse(CourseRequestDto request) {
        LocalDate startDate = request.getStartDate();
        LocalDate endDate = request.getEndDate();
        Integer people = request.getPeople();
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

        courseDatePolicy.validate(startDate, endDate);

        if (people == null || people <= 0) {
            throw new IllegalArgumentException("인원은 1명 이상이어야 합니다.");
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

        List<CourseCandidateDto> stored = dbCandidateService.map(service -> service.find(request)).orElseGet(List::of);
        int target = CourseCandidateShortlistService.targetSize(request);
        boolean needsSupplement = stored.size() < target || !coversSelectedStyles(request,stored);
        log.info("AI_CANDIDATES db={} target={} ktoFallback={}", stored.size(), target, needsSupplement);
        if (!needsSupplement) {
            return prepareCandidates(request, stored);
        }
        List<TourPlaceDto> tourPlaces;
        try {
            tourPlaces = tourApiService.getTourPlaces();
        } catch (KtoApiException exception) {
            // Validator requires every requested day to be non-empty; never fabricate a missing candidate.
            long minimum = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1;
            if (exception.isTemporary() && stored.size() >= minimum) return prepareCandidates(request, stored);
            throw exception;
        }

        if (tourPlaces.isEmpty() && stored.isEmpty()) {
            if (dbCandidateService.isPresent()) throw new KtoApiException(true);
            throw new IllegalArgumentException("조회된 관광지가 없습니다.");
        }

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        List<CourseCandidateDto> courseCandidates = new ArrayList<>(stored);

        List<CourseCandidateShortlistService.ShortlistedPlace> shortlistedPlaces =
                courseCandidateShortlistService.select(request, tourPlaces);

        if (shortlistedPlaces.isEmpty() && stored.isEmpty() && (coursePlacePreferences == null
                || coursePlacePreferences.stream().noneMatch(p -> p != null
                && p.getPreferenceType() == PreferenceType.WANT))) {
            throw new KtoApiException(true);
        }

        for (CourseCandidateShortlistService.ShortlistedPlace shortlisted : shortlistedPlaces) {
            checkCancelled();
            TourPlaceDto place = shortlisted.place();
            if (stored.stream().anyMatch(c -> c.getStoredCandidate().identity().sourceCode().equals("KTO")
                    && c.getStoredCandidate().identity().sourcePlaceId().equals(place.getContentId()))) continue;
            if (!stored.isEmpty() && courseCandidates.size() >= target && shortlisted.preferenceType() != PreferenceType.WANT
                    && request.getCourseStyles().stream().noneMatch(style -> shortlisted.confirmedStyleHints().contains(style.getCode())
                    && courseCandidates.stream().noneMatch(c -> c.getConfirmedStyleHints().contains(style.getCode())))) continue;
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

        if (dbCandidateService.isPresent() && courseCandidates.size() < java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1) {
            throw new KtoApiException(true);
        }
        return prepareCandidates(request, capCandidatesPreservingStyles(request,courseCandidates,target));
    }

    private boolean coversSelectedStyles(CourseRequestDto request,List<CourseCandidateDto> candidates) {
        return request.getCourseStyles().stream().allMatch(style -> candidates.stream()
                .anyMatch(c -> c.getConfirmedStyleHints().contains(style.getCode())));
    }

    private List<CourseCandidateDto> capCandidatesPreservingStyles(CourseRequestDto request,
            List<CourseCandidateDto> candidates,int target) {
        List<CourseCandidateDto> selected = new ArrayList<>(candidates.stream()
                .filter(c -> c.getPreferenceType()==PreferenceType.WANT).toList());
        for(var style:request.getCourseStyles()) {
            if(selected.size()>=target)break;
            if(selected.stream().anyMatch(c -> c.getConfirmedStyleHints().contains(style.getCode())))continue;
            candidates.stream().filter(c -> c.getConfirmedStyleHints().contains(style.getCode())).findFirst().ifPresent(selected::add);
        }
        for(var candidate:candidates) {
            if(selected.size()>=target)break;
            if(!selected.contains(candidate))selected.add(candidate);
        }
        return selected;
    }

    private PreparedCourse prepareCandidates(CourseRequestDto request, List<CourseCandidateDto> courseCandidates) {
        checkCancelled();
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

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("코스 생성 작업이 중단됐습니다.");
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
