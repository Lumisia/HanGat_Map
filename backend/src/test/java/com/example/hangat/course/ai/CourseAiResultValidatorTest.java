package com.example.hangat.course.ai;

import com.example.hangat.course.ai.CourseAiInputDto.CandidateFactDto;
import com.example.hangat.course.ai.CourseAiInputDto.CongestionFactDto;
import com.example.hangat.course.ai.CourseAiInputDto.PlaceConstraintDto;
import com.example.hangat.course.ai.CourseAiInputDto.PlaceIdentityDto;
import com.example.hangat.course.ai.CourseAiInputDto.TripConditionDto;
import com.example.hangat.course.ai.CourseAiInputDto.UserPreferencesDto;
import com.example.hangat.course.ai.CourseAiResultDto.DayDto;
import com.example.hangat.course.ai.CourseAiResultDto.ItemDto;
import com.example.hangat.course.model.PreferenceType;
import com.example.hangat.course.model.Transport;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import com.example.hangat.map.model.enums.CongestionLevel;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CourseAiResultValidatorTest {

    private final CourseAiResultValidator validator = new CourseAiResultValidator();

    @Test
    void acceptsValidResultAndPreservesFixedWant() {
        assertThatCode(() -> validator.validate(input(), validResult()))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsUnknownAndDuplicateCandidates() {
        assertInvalid(result(day("2026-08-28", item("unknown", "09:00"))));
        assertInvalid(result(day("2026-08-28",
                item("want-1", "09:00"), item("want-1", "11:00"))));
    }

    @Test
    void rejectsSameCandidateAcrossDifferentDatesAndTimes() {
        assertInvalid(result(
                day("2026-08-28", item("want-1", "09:00")),
                day("2026-08-29", item("want-1", "11:00"))));
    }

    @Test
    void rejectsMissingWantAndAvoidIdThatIsOutsideCandidateAllowList() {
        assertInvalid(result(day("2026-08-28", item("normal-1", "11:00"))));
        assertInvalid(result(day("2026-08-28",
                item("want-1", "09:00"), item("avoid-1", "11:00"))));
    }

    @Test
    void rejectsChangedFixedDateOrTimeAndOutOfRangeDate() {
        assertInvalid(result(day("2026-08-29", item("want-1", "09:00"))));
        assertInvalid(result(day("2026-08-28", item("want-1", "10:00"))));
        assertInvalid(result(day("2026-08-30", item("want-1", "09:00"))));
    }

    @Test
    void rejectsDuplicateDay() {
        assertInvalid(result(
                day("2026-08-28", item("want-1", "09:00")),
                day("2026-08-28", item("normal-1", "11:00"))));
    }

    @Test
    void rejectsScheduleThatIgnoresDwellOrEstimatedTravelTime() {
        CourseAiInputDto input = inputWithTravel(45);

        assertThatThrownBy(() -> validator.validate(input, result(day("2026-08-28",
                item("want-1", "09:00"), item("normal-1", "10:30")))))
                .isInstanceOfSatisfying(CourseAiValidationException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.getCode())
                                .isEqualTo(CourseAiValidationCode.AI_RESULT_TRAVEL_TIME_OVERLAP));
    }

    @Test
    void rejectsVisitWhoseStyleDwellWouldExceedDayBoundary() {
        CourseAiInputDto unrestricted = inputWithoutFixedTime();
        assertThatThrownBy(() -> validator.validate(unrestricted, result(day("2026-08-28",
                item("want-1", "21:30")))))
                .isInstanceOfSatisfying(CourseAiValidationException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.getCode())
                                .isEqualTo(CourseAiValidationCode.AI_RESULT_DAY_DURATION_EXCEEDED));
    }

    @Test
    void rejectsCrowdedOptionalChoiceWhenVerifiedQuieterCandidateExistsButKeepsWantPriority() {
        CourseAiInputDto input = inputWithCongestion();
        assertThatThrownBy(() -> validator.validate(input, result(day("2026-08-28",
                item("want-1", "09:00"), item("crowded", "12:00")))))
                .isInstanceOfSatisfying(CourseAiValidationException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.getCode())
                                .isEqualTo(CourseAiValidationCode.AI_RESULT_CONGESTION_POLICY_VIOLATION));

        assertThatCode(() -> validator.validate(input, result(day("2026-08-28",
                item("want-1", "09:00"), item("quiet", "12:00")))))
                .doesNotThrowAnyException();
    }

    private void assertInvalid(CourseAiResultDto result) {
        assertThatThrownBy(() -> validator.validate(input(), result))
                .isInstanceOfSatisfying(CourseAiException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.getFailureType())
                                .isEqualTo(CourseAiFailureType.VALIDATION_ERROR));
    }

    private CourseAiInputDto input() {
        PlaceConstraintDto required = new PlaceConstraintDto(
                new PlaceIdentityDto("want-1", 1L, null, null),
                "성산일출봉", "주소", null, null, null,
                PreferenceType.WANT, LocalDate.parse("2026-08-28"), LocalTime.parse("09:00"));
        return new CourseAiInputDto(
                "2.0",
                new TripConditionDto(
                        LocalDate.parse("2026-08-28"), LocalDate.parse("2026-08-29"),
                        2,  Transport.RENTAL_CAR),
                new UserPreferencesDto(List.of(), List.of(), List.of(required), List.of(), null),
                List.of(candidate("want-1", "성산일출봉", PreferenceType.WANT),
                        candidate("normal-1", "만장굴", null)),
                List.of(),
                null
        );
    }

    private CourseAiInputDto inputWithTravel(int minutes) {
        CourseAiInputDto base = input();
        return new CourseAiInputDto(base.contractVersion(), base.tripCondition(), base.userPreferences(),
                base.candidates(), List.of(new CourseAiInputDto.TravelFactDto(
                        "want-1", "normal-1", null, null, minutes,
                        Transport.RENTAL_CAR, null)), base.generationMetadata());
    }

    private CourseAiInputDto inputWithoutFixedTime() {
        CourseAiInputDto base = input();
        PlaceConstraintDto required = new PlaceConstraintDto(
                new PlaceIdentityDto("want-1", 1L, null, null), "성산일출봉", "주소",
                null, null, null, PreferenceType.WANT, null, null);
        return new CourseAiInputDto(base.contractVersion(), base.tripCondition(),
                new UserPreferencesDto(List.of(), List.of(), List.of(required), List.of(), null),
                base.candidates(), base.travelFacts(), base.generationMetadata());
    }

    private CourseAiInputDto inputWithCongestion() {
        CourseAiInputDto base = inputWithoutFixedTime();
        return new CourseAiInputDto(base.contractVersion(), new TripConditionDto(
                LocalDate.parse("2026-08-28"), LocalDate.parse("2026-08-28"), 2,
                Transport.RENTAL_CAR), base.userPreferences(),
                List.of(candidate("want-1", "성산일출봉", PreferenceType.WANT),
                        candidateWithCongestion("crowded", CongestionLevel.CROWDED),
                        candidateWithCongestion("quiet", CongestionLevel.QUIET)),
                List.of(), base.generationMetadata());
    }

    private CandidateFactDto candidateWithCongestion(String id, CongestionLevel level) {
        return new CandidateFactDto(new PlaceIdentityDto(id, null, null, null), id, "주소",
                33.4, 126.8, null, "EAST", null, List.of(),
                List.of(new CongestionFactDto(LocalDate.parse("2026-08-28"), null, level)), null);
    }

    private CandidateFactDto candidate(String id, String name, PreferenceType preferenceType) {
        return new CandidateFactDto(
                new PlaceIdentityDto(id, null, null, null), name, "주소",
                33.4, 126.8, null, "EAST", preferenceType,
                List.of(), List.of(), null);
    }

    private CourseAiResultDto validResult() {
        return result(day("2026-08-28", item("want-1", "09:00")),
                day("2026-08-29", item("normal-1", "09:00")));
    }

    private CourseAiResultDto result(DayDto... days) {
        return new CourseAiResultDto("2.0", List.of(days));
    }

    private DayDto day(String date, ItemDto... items) {
        return new DayDto(LocalDate.parse(date), List.of(items));
    }

    private ItemDto item(String candidateId, String time) {
        return new ItemDto(candidateId, LocalTime.parse(time), "입력 사실 기반 추천");
    }
}
