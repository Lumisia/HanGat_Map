package com.example.hangat.course;

import com.example.hangat.course.ai.CourseAiGenerationService;
import com.example.hangat.course.ai.CourseAiException;
import com.example.hangat.course.ai.CourseAiFailureType;
import com.example.hangat.course.ai.CourseAiResultDto;
import com.example.hangat.course.ai.CourseAiResultValidator;
import com.example.hangat.course.model.CongestionDto;
import com.example.hangat.course.model.CourseRequestDto;
import com.example.hangat.course.model.TourPlaceDto;
import com.example.hangat.course.model.entity.Course;
import com.example.hangat.course.model.entity.CourseItem;
import com.example.hangat.course.model.enums.CourseItemSource;
import com.example.hangat.course.model.enums.CourseStatus;
import com.example.hangat.course.model.enums.CourseType;
import com.example.hangat.course.model.enums.GenerationReason;
import com.example.hangat.course.model.enums.Transport;
import com.example.hangat.map.model.entity.Place;
import com.example.hangat.map.model.entity.Region;
import com.example.hangat.map.goodprice.KakaoLocalClient.KakaoPlace;
import com.example.hangat.course.travel.CourseTravelService;
import com.example.hangat.course.travel.StraightLineDistanceCalculator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.Optional;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;

class CourseServiceAiGenerationFlowTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Test
    void synchronousGenerationVerifiesAndPersistsSelectedAccommodationIdentity() throws Exception {
        CoursePersistenceService persistence = mock(CoursePersistenceService.class);
        CourseBudgetService budget = mock(CourseBudgetService.class);
        CourseResponseAssembler assembler = mock(CourseResponseAssembler.class);
        CourseAccommodationService accommodations = mock(CourseAccommodationService.class);
        CourseService service = spy(new CourseService(
                new StubTourApiService(), new StubCongestionApiService(),
                new CourseCandidateShortlistService(),
                new CourseAiPreparationService(new CourseAiInputAssembler(),
                        new CourseTravelService(new StraightLineDistanceCalculator()), Optional.empty()),
                mock(CourseAiGenerationService.class), persistence, budget, assembler));
        CourseRequestDto request = objectMapper.readValue("""
                {"start_date":"2026-08-27","end_date":"2026-08-27","people":2,
                 "transport":"PUBLIC_TRANSIT","course_regions":[],
                 "course_styles":[{"code":"NATURE","weight":1}],"course_place_preferences":[],
                 "accommodation":{"source_code":"KAKAO_LOCAL","source_place_id":"hotel-real-1",
                    "place_name":"선택 숙소","latitude":33.4,"longitude":126.5}}
                """, CourseRequestDto.class);
        CourseService.ComputedCourse computed = mock(CourseService.ComputedCourse.class);
        var verified = mock(KakaoAccommodationProvider.VerifiedAccommodation.class);
        var verifiedPlace = new KakaoPlace(
                "hotel-real-1", "서버 확인 숙소", "제주 지번", "제주 도로명",
                new java.math.BigDecimal("33.4907"), new java.math.BigDecimal("126.4869"),
                "AD5", "여행 > 숙박", "064", "https://place.map.kakao.com/hotel-real-1", 0);
        var verifiedRegion = Region.builder().code("NORTH").name("북부").displayOrder((byte) 1).build();
        when(verified.place()).thenReturn(verifiedPlace);
        when(verified.region()).thenReturn(verifiedRegion);
        Course course = mock(Course.class);
        when(course.getId()).thenReturn(101L);
        CoursePersistenceResult stored = new CoursePersistenceResult(course, java.util.Map.of(), java.util.Map.of());
        doReturn(computed).when(service).computeCourse(request);
        when(accommodations.verifyGeneratedAccommodation(request.getAccommodation(), computed)).thenReturn(verified);
        when(persistence.persist(request, computed.facts(), computed.result(), computed.metadata(), verified)).thenReturn(stored);
        when(budget.calculateAndCache(101L)).thenReturn(CourseBudgetCalculation.noData());

        // API 컨텍스트에 숙소 서비스가 있으면 선택적 주입 후 실제 검증 경로를 사용해야 한다.
        new ApplicationContextRunner()
                .withBean(CourseService.class, () -> service)
                .withBean(CourseAccommodationService.class, () -> accommodations)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    context.getBean(CourseService.class).createCourse(request);
                });

        verify(accommodations).verifyGeneratedAccommodation(request.getAccommodation(), computed);
        verify(persistence).persist(request, computed.facts(), computed.result(), computed.metadata(), verified);
        verify(assembler).assemble(any(), any(), any(), argThat(accommodation ->
                "hotel-real-1".equals(accommodation.getSourcePlaceId())
                        && "서버 확인 숙소".equals(accommodation.getPlaceName())
                        && "NORTH".equals(accommodation.getRegion())), any());
    }

    @Test
    void createCoursePreparesInputThenInvokesProviderAndValidator() throws Exception {
        AtomicBoolean providerCalled = new AtomicBoolean();
        CourseAiGenerationService generationService = new CourseAiGenerationService(
                input -> {
                    assertThat(input.candidates())
                            .extracting(candidate -> candidate.identity().candidateId())
                            .containsExactlyInAnyOrder("candidate-1", "candidate-2", "candidate-3");
                    providerCalled.set(true);
                    return new CourseAiResultDto(input.contractVersion(), List.of(
                            new CourseAiResultDto.DayDto(
                                    LocalDate.of(2026, 8, 27),
                                    List.of(new CourseAiResultDto.ItemDto(
                                            "candidate-1",
                                            LocalTime.of(9, 0),
                                            "한글 추천 이유"))),
                            new CourseAiResultDto.DayDto(
                                    LocalDate.of(2026, 8, 28),
                                    List.of(new CourseAiResultDto.ItemDto(
                                            "candidate-2", LocalTime.of(9, 0), "둘째 날 추천 이유"))),
                            new CourseAiResultDto.DayDto(
                                    LocalDate.of(2026, 8, 29),
                                    List.of(new CourseAiResultDto.ItemDto(
                                            "candidate-3", LocalTime.of(9, 0), "셋째 날 추천 이유")))));
                },
                new CourseAiResultValidator());
        CoursePersistenceService persistenceService = mock(CoursePersistenceService.class);
        Course course = mock(Course.class);
        when(course.getId()).thenReturn(101L);
        when(course.getCourseType()).thenReturn(CourseType.USER);
        when(course.getGenerationReason()).thenReturn(GenerationReason.INITIAL);
        when(course.getStatus()).thenReturn(CourseStatus.READY);
        when(course.getStartDate()).thenReturn(LocalDate.of(2026, 8, 27));
        when(course.getEndDate()).thenReturn(LocalDate.of(2026, 8, 29));
        when(course.getPeople()).thenReturn((short) 2);
        when(course.getTransport()).thenReturn(Transport.RENTAL_CAR);
        // 저장 결과도 3일의 서로 다른 장소를 반환해야 응답 조립까지 검증할 수 있다.
        CourseItem firstItem = persistedItem(course, 201L, 301L, (short) 1,
                LocalDate.of(2026, 8, 27), "한글 추천 이유");
        CourseItem secondItem = persistedItem(course, 202L, 302L, (short) 2,
                LocalDate.of(2026, 8, 28), "둘째 날 추천 이유");
        CourseItem thirdItem = persistedItem(course, 203L, 303L, (short) 3,
                LocalDate.of(2026, 8, 29), "셋째 날 추천 이유");
        when(persistenceService.persist(
                any(CourseRequestDto.class),
                any(com.example.hangat.course.facts.CourseGenerationFacts.class),
                any(CourseAiResultDto.class),
                any(CourseGenerationMetadata.class)))
                .thenReturn(new CoursePersistenceResult(
                        course,
                        java.util.Map.of("candidate-1", firstItem,
                                "candidate-2", secondItem, "candidate-3", thirdItem),
                        java.util.Map.of("candidate-1", "관광지",
                                "candidate-2", "관광지", "candidate-3", "관광지")));
        CourseBudgetService budgetService = mock(CourseBudgetService.class);
        when(budgetService.calculateAndCache(101L))
                .thenReturn(CourseBudgetCalculation.noData());
        CourseService service = new CourseService(
                new StubTourApiService(),
                new StubCongestionApiService(),
                new CourseCandidateShortlistService(),
                new CourseAiPreparationService(
                        new CourseAiInputAssembler(),
                        new CourseTravelService(new StraightLineDistanceCalculator()),
                        Optional.empty()),
                generationService,
                persistenceService,
                budgetService,
                new CourseResponseAssembler());

        var response = service.createCourse(request());

        assertThat(providerCalled).isTrue();
        verify(persistenceService).persist(
                any(CourseRequestDto.class),
                org.mockito.ArgumentMatchers.argThat(facts ->
                        facts.candidates().stream().map(candidate -> candidate.identity().candidateId())
                                .collect(java.util.stream.Collectors.toSet())
                                .equals(java.util.Set.of("candidate-1", "candidate-2", "candidate-3"))),
                org.mockito.ArgumentMatchers.argThat(result ->
                        result.days().stream().map(CourseAiResultDto.DayDto::date).toList()
                                .equals(List.of(LocalDate.of(2026, 8, 27),
                                        LocalDate.of(2026, 8, 28), LocalDate.of(2026, 8, 29)))
                                && result.days().stream().flatMap(day -> day.items().stream())
                                .map(CourseAiResultDto.ItemDto::recommendationReason).toList()
                                .equals(List.of("한글 추천 이유", "둘째 날 추천 이유", "셋째 날 추천 이유"))),
                org.mockito.ArgumentMatchers.argThat(metadata ->
                        metadata.generationReason()
                                == com.example.hangat.course.model.GenerationReason.INITIAL));
        assertThat(response.days()).hasSize(3);
        assertThat(response.days()).extracting(day -> day.dayNo())
                .containsExactly(1, 2, 3);
        assertThat(response.days()).allSatisfy(day -> assertThat(day.items()).hasSize(1));
        assertThat(response.days().stream().flatMap(day -> day.items().stream()))
                .extracting(item -> item.visitDate())
                .containsExactly(LocalDate.of(2026, 8, 27),
                        LocalDate.of(2026, 8, 28), LocalDate.of(2026, 8, 29));
        assertThat(response.days().stream().flatMap(day -> day.items().stream()))
                .extracting(item -> item.placeName())
                .containsExactly("만장굴", "성산일출봉", "비자림");
        assertThat(response.budgetSummary().hasCostData()).isFalse();
        assertThat(response.days().get(0).items().get(0).placeName()).isEqualTo("만장굴");
        assertThat(response.days().get(0).items().get(0).recommendationReason())
                .isEqualTo("한글 추천 이유");
    }

    @Test
    void exhaustedProviderFailureDoesNotStartCoursePersistence() throws Exception {
        CourseAiGenerationService generationService = mock(CourseAiGenerationService.class);
        when(generationService.generate(any())).thenThrow(new CourseAiException(
                CourseAiFailureType.TEMPORARILY_UNAVAILABLE,
                "Gemini transient attempts exhausted"));
        CoursePersistenceService persistenceService = mock(CoursePersistenceService.class);
        CourseBudgetService budgetService = mock(CourseBudgetService.class);
        CourseService service = new CourseService(
                new StubTourApiService(),
                new StubCongestionApiService(),
                new CourseCandidateShortlistService(),
                new CourseAiPreparationService(
                        new CourseAiInputAssembler(),
                        new CourseTravelService(new StraightLineDistanceCalculator()),
                        Optional.empty()),
                generationService,
                persistenceService,
                budgetService,
                new CourseResponseAssembler());

        assertThatThrownBy(() -> service.createCourse(request()))
                .isInstanceOfSatisfying(CourseAiException.class, exception ->
                        assertThat(exception.getFailureType())
                                .isEqualTo(CourseAiFailureType.TEMPORARILY_UNAVAILABLE));
        verifyNoInteractions(persistenceService, budgetService);
    }

    private CourseRequestDto request() throws Exception {
        return objectMapper.readValue("""
                {
                  "start_date": "2026-08-27",
                  "end_date": "2026-08-29",
                  "people": 2,
                  "transport": "RENTAL_CAR",
                  "course_regions": [],
                  "course_styles": [{"code": "NATURE", "weight": 1}],
                  "course_place_preferences": []
                }
                """, CourseRequestDto.class);
    }

    private CourseItem persistedItem(Course course, long itemId, long placeId,
                                     short dayNo, LocalDate visitDate, String reason) {
        Place place = mock(Place.class);
        when(place.getId()).thenReturn(placeId);
        CourseItem item = mock(CourseItem.class);
        when(item.getId()).thenReturn(itemId);
        when(item.getCourse()).thenReturn(course);
        when(item.getPlace()).thenReturn(place);
        when(item.getDayNo()).thenReturn(dayNo);
        when(item.getPosition()).thenReturn((short) 1);
        when(item.getVisitDate()).thenReturn(visitDate);
        when(item.getStartTime()).thenReturn(LocalTime.of(9, 0));
        when(item.getItemSource()).thenReturn(CourseItemSource.AI_RECOMMENDED);
        when(item.getRecommendationReason()).thenReturn(reason);
        return item;
    }

    private final class StubTourApiService extends TourApiService {
        @Override
        public List<TourPlaceDto> getTourPlaces() {
            try {
                return List.of(objectMapper.readValue("""
                        {"contentid":"candidate-1","title":"만장굴","addr1":"주소 미상",
                         "mapy":33.529,"mapx":126.771,"cat1":"A01"}
                        """, TourPlaceDto.class),
                        objectMapper.readValue("""
                        {"contentid":"candidate-2","title":"성산일출봉","addr1":"주소 미상",
                         "mapy":33.458,"mapx":126.942,"cat1":"A01"}
                        """, TourPlaceDto.class),
                        objectMapper.readValue("""
                        {"contentid":"candidate-3","title":"비자림","addr1":"주소 미상",
                         "mapy":33.491,"mapx":126.811,"cat1":"A01"}
                        """, TourPlaceDto.class));
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }
    }

    private static final class StubCongestionApiService extends CongestionApiService {
        @Override
        public List<CongestionDto> getCongestionData(String signguCd, String name) {
            return List.of();
        }
    }
}
