package com.example.hangat.course;

import com.example.hangat.course.ai.CourseAiInputDto;
import com.example.hangat.course.ai.CourseAiGenerationService;
import com.example.hangat.course.ai.CourseAiResultValidator;
import com.example.hangat.course.facts.WeatherFact;
import com.example.hangat.course.facts.WeatherFactSet;
import com.example.hangat.course.model.CongestionDto;
import com.example.hangat.course.model.CourseCandidateDto;
import com.example.hangat.course.model.CourseRequestDto;
import com.example.hangat.course.model.TourPlaceDto;
import com.example.hangat.course.travel.CourseTravelService;
import com.example.hangat.course.travel.StraightLineDistanceCalculator;
import com.example.hangat.course.weather.CourseWeatherDto;
import com.example.hangat.course.weather.CourseWeatherFacts;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CourseAiPreparationServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void preparesOperationalAiInputWithoutInventingUnavailableFacts() throws Exception {
        List<TourPlaceDto> tourPlaces = List.of(
                place("east-want", "성산일출봉", "제주특별자치도 서귀포시 성산읍", 33.458, 126.942, "A01"),
                place("east-avoid", "비자림", "제주특별자치도 제주시 구좌읍", 33.491, 126.811, "A01"),
                place("east-normal", "만장굴", "제주특별자치도 제주시 구좌읍", 33.529, 126.771, "A01")
        );
        List<CongestionDto> wantCongestion = List.of(congestion("20260828", "82.00"));
        TourApiService tourApiService = new TourApiService() {
            @Override
            public List<TourPlaceDto> getTourPlaces() {
                return tourPlaces;
            }
        };
        CongestionApiService congestionApiService = new CongestionApiService() {
            @Override
            public List<CongestionDto> getCongestionData(String signguCd, String name) {
                return "성산일출봉".equals(name)
                        ? wantCongestion
                        : List.of();
            }
        };
        CourseAiPreparationService preparationService = new CourseAiPreparationService(
                new CourseAiInputAssembler(),
                new CourseTravelService(new StraightLineDistanceCalculator()),
                Optional.empty());
        CourseService courseService = new CourseService(
                tourApiService, congestionApiService,
                new CourseCandidateShortlistService(), preparationService,
                new CourseAiGenerationService(
                        input -> { throw new AssertionError("provider must not run in preparation test"); },
                        new CourseAiResultValidator()),
                org.mockito.Mockito.mock(CoursePersistenceService.class),
                org.mockito.Mockito.mock(CourseBudgetService.class),
                new CourseResponseAssembler());

        CourseAiInputDto result = courseService.prepareAiInput(request());

        assertThat(result.candidates()).extracting("name")
                .containsExactly("성산일출봉", "만장굴");
        assertThat(result.contractVersion()).isEqualTo("2.0");
        assertThat(result.trip().startDate()).isEqualTo(LocalDate.of(2026, 8, 27));
        assertThat(result.trip().endDate()).isEqualTo(LocalDate.of(2026, 8, 29));
        assertThat(result.trip().transport()).isEqualTo(com.example.hangat.course.model.Transport.RENTAL_CAR);
        assertThat(result.preferences().selectedRegionCodes()).containsExactly("EAST");
        assertThat(result.preferences().selectedStyleCodes()).containsExactly("NATURE");
        assertThat(result.hardConstraints().requiredCandidates()).hasSize(1);
        assertThat(result.hardConstraints().requiredCandidates().get(0).candidateId())
                .isEqualTo("east-want");
        assertThat(result.hardConstraints().requiredCandidates().get(0).fixedDate().toString())
                .isEqualTo("2026-08-28");
        assertThat(result.hardConstraints().requiredCandidates().get(0).fixedTime())
                .isEqualTo(LocalTime.of(9, 0));
        assertThat(result.candidates()).extracting(CourseAiInputDto.CandidateFactDto::candidateId)
                .doesNotContain("east-avoid");
        assertThat(result.candidates().get(0).styleHintCodes()).containsExactly("NATURE");
        assertThat(result.candidates().get(0).internalCategoryCode()).isEqualTo("TOURIST");
        assertThat(result.candidates().get(0).congestionFacts().get(0).rate())
                .isEqualByComparingTo("82.00");
        assertThat(result.candidates()).allSatisfy(candidate ->
                assertThat(candidate.weatherFactSetId()).isNull());
        assertThat(result.weatherFactSets()).isEmpty();

        assertThat(result.travelFacts()).hasSize(2);
        CourseAiInputDto.TravelFactDto travel = result.travelFacts().get(0);
        assertThat(travel.fromRef()).isEqualTo("east-want");
        assertThat(travel.toRef()).isEqualTo("east-normal");
        assertThat(travel.straightDistanceMeters()).isPositive();
        assertThat(travel.routeDistanceMeters()).isNull();
        assertThat(travel.travelMinutes()).isPositive();
        assertThat(result.candidates()).extracting(CourseAiInputDto.CandidateFactDto::candidateId)
                .contains(travel.fromRef(), travel.toRef());

        String json = objectMapper.writeValueAsString(result);
        assertThat(json).contains("\"straightDistanceMeters\"");
        assertThat(json).contains("\"routeDistanceMeters\":null");
        assertThat(json).contains("\"travelMinutes\":");
        assertThat(json).doesNotContain(
                "tripCondition", "userPreferences", "generationMetadata",
                "identity", "placeId", "sourcePlaceId", "sourceCode",
                "tourCategory", "weather\"", "straightDistanceKm",
                "routeDistanceKm", "durationMinutes", "people", "budgetTotal");
        assertThat(json.toLowerCase())
                .doesNotContain("servicekey", "authorization", "credential", "gemini");
    }

    @Test
    void includesWeatherOnlyWhenVerifiedFactsProviderSuppliesIt() throws Exception {
        TourPlaceDto place = place(
                "east-normal", "만장굴", "제주특별자치도 제주시 구좌읍",
                33.529, 126.771, "A01");
        CourseCandidateDto candidate = new CourseCandidateDto(
                place, List.of(), null, List.of("NATURE"));
        CourseWeatherDto weather = new CourseWeatherDto(
                LocalDate.of(2026, 8, 28), LocalTime.of(12, 0),
                new BigDecimal("28.5"), 20, "0", "1",
                new BigDecimal("3.2"), 70);
        CourseAiPreparationService preparationService = new CourseAiPreparationService(
                new CourseAiInputAssembler(),
                new CourseTravelService(new StraightLineDistanceCalculator()),
                Optional.of((request, candidates) -> new CourseWeatherFacts(
                        Map.of("east-normal", "kma-east-20260827-0500"),
                        List.of(new WeatherFactSet(
                                "kma-east-20260827-0500", "KMA", 55, 38,
                                LocalDate.of(2026, 8, 27), LocalTime.of(5, 0),
                                List.of(new WeatherFact(
                                        null,
                                        weather.forecastDate(), weather.forecastTime(),
                                        weather.temperature(), weather.precipitationProbability(),
                                        weather.precipitationTypeCode(), weather.skyConditionCode(),
                                        weather.windSpeed(), weather.humidity())))))));

        CourseAiPreparationService.PreparedGeneration prepared =
                preparationService.prepareGeneration(request(), List.of(candidate));
        CourseAiInputDto result = prepared.input();

        assertThat(result.candidates().get(0).weatherFactSetId())
                .isEqualTo("kma-east-20260827-0500");
        assertThat(prepared.facts().candidates()).hasSize(1);
        assertThat(prepared.facts().candidates().get(0).identity().candidateId())
                .isEqualTo("east-normal");
        assertThat(prepared.facts().weatherFactSets()).hasSize(1);
        assertThat(prepared.metadata().generationReason())
                .isEqualTo(com.example.hangat.course.model.GenerationReason.INITIAL);
        assertThat(result.weatherFactSets()).hasSize(1);
        assertThat(result.weatherFactSets().get(0).weatherFactSetId())
                .isEqualTo("kma-east-20260827-0500");
        assertThat(result.weatherFactSets().get(0).facts()).hasSize(1);
        assertThat(result.weatherFactSets().get(0).facts().get(0).temperature())
                .isEqualByComparingTo("28.5");
        String json = objectMapper.writeValueAsString(result);
        assertThat(json).contains("\"weatherFactSets\"");
        assertThat(json).doesNotContain("\"humidity\"", "\"gridX\"", "\"baseDate\"");
    }

    @Test
    void marksExplicitSameConditionRegenerationWithoutChangingTheRequestContract() throws Exception {
        CourseRequestDto request = objectMapper.readValue("""
                {
                  "start_date":"2026-08-27","end_date":"2026-08-27","people":2,
                  "transport":"PUBLIC_TRANSIT","regenerate":true,
                  "course_regions":[],"course_styles":[{"code":"NATURE"}],
                  "course_place_preferences":[]
                }
                """, CourseRequestDto.class);
        CourseCandidateDto candidate = new CourseCandidateDto(
                place("one", "장소", "제주특별자치도", 33.4, 126.6, "A01"),
                List.of(), null, List.of("NATURE"));
        CourseAiPreparationService service = new CourseAiPreparationService(
                new CourseAiInputAssembler(),
                new CourseTravelService(new StraightLineDistanceCalculator()), Optional.empty());

        CourseAiPreparationService.PreparedGeneration prepared =
                service.prepareGeneration(request, List.of(candidate));

        assertThat(prepared.metadata().generationReason())
                .isEqualTo(com.example.hangat.course.model.GenerationReason.USER_REGENERATE);
        assertThat(prepared.input().generationMetadata().generationReason())
                .isEqualTo(com.example.hangat.course.model.GenerationReason.USER_REGENERATE);
    }

    private CourseRequestDto request() throws Exception {
        return objectMapper.readValue("""
                {
                  "start_date": "2026-08-27",
                  "end_date": "2026-08-29",
                  "people": 2,
                  "transport": "RENTAL_CAR",
                  "course_regions": [{"region_id": 2, "code": "EAST", "name": "동부"}],
                  "course_styles": [{"tag_id": 1, "code": "NATURE", "name": "자연", "weight": 1}],
                  "course_place_preferences": [
                    {"place_name": "성산일출봉", "preference_type": "WANT", "fixed_date": "2026-08-28", "fixed_time": "09:00"},
                    {"place_name": "비자림", "preference_type": "AVOID"}
                  ]
                }
                """, CourseRequestDto.class);
    }

    private TourPlaceDto place(
            String id, String title, String address,
            double latitude, double longitude, String category
    ) throws Exception {
        return objectMapper.readValue("""
                {"contentid":"%s","title":"%s","addr1":"%s",
                 "mapy":%s,"mapx":%s,"cat1":"%s"}
                """.formatted(id, title, address, latitude, longitude, category),
                TourPlaceDto.class);
    }

    private CongestionDto congestion(String date, String rate) throws Exception {
        return objectMapper.readValue(
                "{\"baseYmd\":\"%s\",\"cnctrRate\":\"%s\"}"
                        .formatted(date, rate),
                CongestionDto.class);
    }
}
