package com.example.hangat.course;

import com.example.hangat.course.model.CongestionDto;
import com.example.hangat.course.ai.CourseAiGenerationService;
import com.example.hangat.course.ai.CourseAiResultValidator;
import com.example.hangat.course.model.CourseRequestDto;
import com.example.hangat.course.model.TourPlaceDto;
import com.example.hangat.course.travel.CourseTravelService;
import com.example.hangat.course.travel.StraightLineDistanceCalculator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class CourseServiceValidationTest {

    @Test
    void requestContractNoLongerContainsTotalBudget() throws Exception {
        var json = objectMapper.valueToTree(preferences(""));
        org.assertj.core.api.Assertions.assertThat(json.has("budget_total")).isFalse();
    }

    @Test
    void ignoresTotalBudgetInPreviouslyQueuedRequests() throws Exception {
        var legacy = objectMapper.valueToTree(preferences(""));
        ((com.fasterxml.jackson.databind.node.ObjectNode) legacy).put("budget_total", 400000);
        var request = objectMapper.treeToValue(legacy, CourseRequestDto.class);
        assertThatCode(() -> courseService.prepareAiInput(request)).doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThat(objectMapper.valueToTree(request).has("budget_total")).isFalse();
    }

    @Test
    void acceptsMissingBudgetAndEmptyPlacePreferences() throws Exception {
        CourseRequestDto request = preferences("");
        assertThatCode(() -> courseService.prepareAiInput(request)).doesNotThrowAnyException();
        var input = courseService.prepareAiInput(request);
        var prompt = new com.example.hangat.course.ai.CourseAiPrompt(objectMapper).userPrompt(input);
        var json = objectMapper.readTree(prompt);
        org.assertj.core.api.Assertions.assertThat(input.userPreferences().requiredPlaces()).isEmpty();
        org.assertj.core.api.Assertions.assertThat(input.userPreferences().forbiddenPlaces()).isEmpty();
        var required = json.path("hardConstraints").path("requiredCandidates");
        org.assertj.core.api.Assertions.assertThat(required.isArray()).isTrue();
        org.assertj.core.api.Assertions.assertThat(required.size()).isZero();
        var async = mock(AsyncCourseService.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        assertThatCode(() -> org.springframework.test.util.ReflectionTestUtils.invokeMethod(async, "validateRequest", request))
                .doesNotThrowAnyException();
    }

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());
    private final CourseService courseService = new CourseService(
            new StubTourApiService(), new StubCongestionApiService(),
            new CourseCandidateShortlistService(),
            new CourseAiPreparationService(
                    new CourseAiInputAssembler(),
                    new CourseTravelService(new StraightLineDistanceCalculator()),
                    Optional.empty()),
            new CourseAiGenerationService(
                    input -> { throw new AssertionError("provider must not run in validation test"); },
                    new CourseAiResultValidator()),
            mock(CoursePersistenceService.class),
            mock(CourseBudgetService.class),
            new CourseResponseAssembler());

    @Test
    void rejectsDuplicateWantByInternalId() throws Exception {
        assertInvalid(preferences("""
                {"place_id": 1, "place_name": "비자림", "preference_type": "WANT"},
                {"place_id": 1, "place_name": "다른 이름", "preference_type": "WANT"}
                """));
    }

    @Test
    void rejectsDuplicateAvoidByInternalId() throws Exception {
        assertInvalid(preferences("""
                {"place_id": 1, "place_name": "비자림", "preference_type": "AVOID"},
                {"place_id": 1, "place_name": "다른 이름", "preference_type": "AVOID"}
                """));
    }

    @Test
    void rejectsDuplicateExternalIdentity() throws Exception {
        assertInvalid(preferences("""
                {"source_code": "KTO", "source_place_id": "125266", "place_name": "비자림", "preference_type": "WANT"},
                {"source_code": "kto", "source_place_id": "125266", "place_name": "다른 이름", "preference_type": "WANT"}
                """));
    }

    @Test
    void rejectsDuplicateByNormalizedNameWhenNoCommonIdExists() throws Exception {
        assertInvalid(preferences("""
                {"place_name": "비 자 림", "preference_type": "WANT"},
                {"place_name": "비자림", "preference_type": "WANT"}
                """));
    }

    @Test
    void doesNotCrossCompareInternalAndExternalIds() throws Exception {
        assertThatCode(() -> courseService.prepareAiInput(preferences("""
                {"place_id": 125266, "place_name": "내부 장소", "preference_type": "WANT"},
                {"source_code": "KTO", "source_place_id": "125266", "place_name": "외부 장소", "preference_type": "WANT"}
                """))).doesNotThrowAnyException();
    }

    @Test
    void keepsWantAvoidConflictRejection() throws Exception {
        assertInvalid(preferences("""
                {"place_id": 1, "place_name": "비자림", "preference_type": "WANT"},
                {"place_id": 1, "place_name": "비자림", "preference_type": "AVOID"}
                """));
    }

    @Test
    void acceptsDifferentPlaces() throws Exception {
        assertThatCode(() -> courseService.prepareAiInput(preferences("""
                {"place_id": 1, "place_name": "비자림", "preference_type": "WANT"},
                {"place_id": 2, "place_name": "성산일출봉", "preference_type": "AVOID"}
                """))).doesNotThrowAnyException();
    }

    @Test
    void acceptsFixedDateAtTripBoundaries() throws Exception {
        assertThatCode(() -> courseService.prepareAiInput(preferences("""
                {"place_id": 1, "place_name": "비자림", "preference_type": "WANT", "fixed_date": "2026-08-27"},
                {"place_id": 2, "place_name": "성산일출봉", "preference_type": "WANT", "fixed_date": "2026-08-29", "fixed_time": "10:30"}
                """))).doesNotThrowAnyException();
    }

    @Test
    void acceptsWantWithoutFixedSchedule() throws Exception {
        assertThatCode(() -> courseService.prepareAiInput(preferences("""
                {"place_id": 1, "place_name": "비자림", "preference_type": "WANT"}
                """))).doesNotThrowAnyException();
    }

    @Test
    void rejectsFixedDateBeforeAndAfterTrip() throws Exception {
        assertInvalid(preferences("""
                {"place_id": 1, "place_name": "비자림", "preference_type": "WANT", "fixed_date": "2026-08-26"}
                """));
        assertInvalid(preferences("""
                {"place_id": 1, "place_name": "비자림", "preference_type": "WANT", "fixed_date": "2026-08-30"}
                """));
    }

    @Test
    void rejectsFixedTimeWithoutDate() throws Exception {
        assertInvalid(preferences("""
                {"place_id": 1, "place_name": "비자림", "preference_type": "WANT", "fixed_time": "10:30"}
                """));
    }

    @Test
    void rejectsAvoidFixedDateOrTime() throws Exception {
        assertInvalid(preferences("""
                {"place_id": 1, "place_name": "비자림", "preference_type": "AVOID", "fixed_date": "2026-08-27"}
                """));
        assertInvalid(preferences("""
                {"place_id": 1, "place_name": "비자림", "preference_type": "AVOID", "fixed_time": "10:30"}
                """));
    }

    private void assertInvalid(CourseRequestDto request) {
        assertThatThrownBy(() -> courseService.prepareAiInput(request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private CourseRequestDto preferences(String preferences) throws Exception {
        return objectMapper.readValue("""
                {
                  "start_date": "2026-08-27",
                  "end_date": "2026-08-29",
                  "people": 2,
                  "transport": "RENTAL_CAR",
                  "course_regions": [],
                  "course_styles": [{"code": "NATURE", "weight": 1}],
                  "course_place_preferences": [%s]
                }
                """.formatted(preferences), CourseRequestDto.class);
    }

    private final class StubTourApiService extends TourApiService {
        @Override
        public List<TourPlaceDto> getTourPlaces() {
            try {
                return List.of(objectMapper.readValue("""
                        {"contentid":"125266","title":"비자림","addr1":"주소 미상",
                         "mapy":33.485,"mapx":126.811,"cat1":"A01"}
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
