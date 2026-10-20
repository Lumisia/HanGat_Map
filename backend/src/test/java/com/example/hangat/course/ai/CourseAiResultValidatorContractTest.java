package com.example.hangat.course.ai;

import com.example.hangat.course.ai.CourseAiInputDto.CandidateFactDto;
import com.example.hangat.course.ai.CourseAiInputDto.HardConstraintsDto;
import com.example.hangat.course.ai.CourseAiInputDto.PlaceConstraintDto;
import com.example.hangat.course.ai.CourseAiInputDto.PlaceIdentityDto;
import com.example.hangat.course.ai.CourseAiInputDto.RequiredCandidateConstraintDto;
import com.example.hangat.course.ai.CourseAiInputDto.TripConditionDto;
import com.example.hangat.course.ai.CourseAiInputDto.UserPreferencesDto;
import com.example.hangat.course.ai.CourseAiResultDto.DayDto;
import com.example.hangat.course.ai.CourseAiResultDto.ItemDto;
import com.example.hangat.course.model.PreferenceType;
import com.example.hangat.course.model.Transport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CourseAiResultValidatorContractTest {

    private final CourseAiResultValidator validator = new CourseAiResultValidator();

    @Test
    void rejectsMissingResultAndContractViolationsWithStableCodes() {
        assertInvalid(input(), null, CourseAiValidationCode.AI_RESULT_MISSING);
        assertInvalid(input(), new CourseAiResultDto(null, List.of()),
                CourseAiValidationCode.AI_RESULT_CONTRACT_VERSION_MISSING);
        assertInvalid(input(), new CourseAiResultDto("1.0", List.of()),
                CourseAiValidationCode.AI_RESULT_CONTRACT_VERSION_MISMATCH);
        assertInvalid(input(), new CourseAiResultDto("2.0", null),
                CourseAiValidationCode.AI_RESULT_DAYS_MISSING);
        assertInvalid(input(), new CourseAiResultDto("2.0", List.of()),
                CourseAiValidationCode.AI_RESULT_DAYS_EMPTY);
    }

    @Test
    void rejectsInvalidDayStructureRangeDuplicatesAndOrder() {
        assertInvalid(input(), result(Arrays.asList((DayDto) null)),
                CourseAiValidationCode.AI_RESULT_DAY_MISSING);
        assertInvalid(input(), result(List.of(new DayDto(null, List.of(item("want-1", "09:00"))))),
                CourseAiValidationCode.AI_RESULT_DAY_DATE_MISSING);
        assertInvalid(input(), result(List.of(day("2026-08-26", item("want-1", "09:00")))),
                CourseAiValidationCode.AI_RESULT_DAY_OUT_OF_RANGE);
        assertInvalid(input(), result(List.of(
                        day("2026-08-28", item("want-1", "09:00")),
                        day("2026-08-28", item("normal-1", "11:00")))),
                CourseAiValidationCode.AI_RESULT_DUPLICATE_DAY);
        assertInvalid(input(), result(List.of(
                        day("2026-08-29", item("normal-1", "11:00")),
                        day("2026-08-28", item("want-1", "09:00")))),
                CourseAiValidationCode.AI_RESULT_DAY_ORDER_INVALID);
        assertInvalid(input(), result(List.of(new DayDto(date("2026-08-28"), null))),
                CourseAiValidationCode.AI_RESULT_DAY_ITEMS_MISSING);
        assertInvalid(input(), result(List.of(new DayDto(date("2026-08-28"), List.of()))),
                CourseAiValidationCode.AI_RESULT_DAY_ITEMS_EMPTY);
    }

    @Test
    void rejectsInvalidCandidateIdsAndDuplicatesAcrossWholeTrip() {
        assertInvalid(input(), result(List.of(day("2026-08-28",
                        new ItemDto(null, LocalTime.of(9, 0), "근거")))),
                CourseAiValidationCode.AI_RESULT_CANDIDATE_ID_MISSING);
        assertInvalid(input(), result(List.of(day("2026-08-28", item("unknown", "09:00")))),
                CourseAiValidationCode.AI_RESULT_UNKNOWN_CANDIDATE);
        assertInvalid(input(), result(List.of(day("2026-08-28",
                        item("want-1", "09:00"), item("want-1", "11:00")))),
                CourseAiValidationCode.AI_RESULT_DUPLICATE_CANDIDATE);
        assertInvalid(input(), result(List.of(
                        day("2026-08-28", item("want-1", "09:00")),
                        day("2026-08-29", item("want-1", "11:00")))),
                CourseAiValidationCode.AI_RESULT_DUPLICATE_CANDIDATE);
    }

    @Test
    void rejectsInvalidCandidateAllowListAndRequiredConstraintInput() {
        CourseAiInputDto base = input();
        CourseAiInputDto duplicateCandidates = copyInput(
                base,
                List.of(base.candidates().get(0), base.candidates().get(0)),
                base.hardConstraints());
        assertInvalid(duplicateCandidates, validSingleItemResult(),
                CourseAiValidationCode.AI_RESULT_INPUT_DUPLICATE_CANDIDATE);

        CourseAiInputDto missingCandidateId = copyInput(
                base,
                List.of(candidate(" ", "잘못된 후보")),
                new HardConstraintsDto(List.of()));
        assertInvalid(missingCandidateId, validSingleItemResult(),
                CourseAiValidationCode.AI_RESULT_INPUT_CANDIDATE_ID_MISSING);

        CourseAiInputDto fixedTimeWithoutDate = copyInput(
                base,
                base.candidates(),
                new HardConstraintsDto(List.of(new RequiredCandidateConstraintDto(
                        "want-1", null, LocalTime.of(9, 0)))));
        assertInvalid(fixedTimeWithoutDate, validSingleItemResult(),
                CourseAiValidationCode.AI_RESULT_INPUT_REQUIRED_CANDIDATE_INVALID);
    }

    @Test
    void rejectsMissingReverseAndDuplicateStartTimes() {
        assertInvalid(input(), result(List.of(day("2026-08-28",
                        new ItemDto("want-1", null, "근거")))),
                CourseAiValidationCode.AI_RESULT_START_TIME_MISSING);
        assertInvalid(input(), result(List.of(day("2026-08-28",
                        item("want-1", "11:00"), item("normal-1", "10:00")))),
                CourseAiValidationCode.AI_RESULT_TIME_ORDER_INVALID);
        assertInvalid(input(), result(List.of(day("2026-08-28",
                        item("want-1", "09:00"), item("normal-1", "09:00")))),
                CourseAiValidationCode.AI_RESULT_DUPLICATE_START_TIME);
    }

    @Test
    void enforcesRecommendationReasonPresenceAndThreeHundredCharacterLimit() {
        assertInvalid(input(), result(List.of(day("2026-08-28",
                        new ItemDto("want-1", LocalTime.of(9, 0), null)))),
                CourseAiValidationCode.AI_RESULT_REASON_MISSING);
        assertInvalid(input(), result(List.of(day("2026-08-28",
                        new ItemDto("want-1", LocalTime.of(9, 0), "  ")))),
                CourseAiValidationCode.AI_RESULT_REASON_MISSING);
        assertInvalid(input(), result(List.of(day("2026-08-28",
                        new ItemDto("want-1", LocalTime.of(9, 0), "가".repeat(301))))),
                CourseAiValidationCode.AI_RESULT_REASON_TOO_LONG);
        assertThatCode(() -> validator.validate(singleDayInput(), result(List.of(day("2026-08-28",
                        new ItemDto("want-1", LocalTime.of(9, 0), "가".repeat(300)))))))
                .doesNotThrowAnyException();
    }

    @Test
    void enforcesRequiredCandidateAndFixedSchedule() {
        assertInvalid(input(), result(List.of(day("2026-08-28", item("normal-1", "11:00")))),
                CourseAiValidationCode.AI_RESULT_REQUIRED_CANDIDATE_MISSING);
        assertInvalid(input(), result(List.of(day("2026-08-29", item("want-1", "09:00")))),
                CourseAiValidationCode.AI_RESULT_FIXED_DATE_CHANGED);
        assertInvalid(input(), result(List.of(day("2026-08-28", item("want-1", "10:00")))),
                CourseAiValidationCode.AI_RESULT_FIXED_TIME_CHANGED);
        CourseAiResultDto fixedResult = result(List.of(
                day("2026-08-28", item("want-1", "09:00"))));
        assertThatCode(() -> validator.validate(singleDayInput(), fixedResult))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsOrderedResultMissingTheFirstTripDate() {
        CourseAiResultDto multiDayResult = result(List.of(
                day("2026-08-28", item("want-1", "09:00"), item("normal-1", "11:00")),
                day("2026-08-29", item("normal-2", "10:00"))));
        assertInvalid(input(), multiDayResult, CourseAiValidationCode.AI_RESULT_TRIP_DATE_MISSING);
    }

    @Test
    void acceptsThreePlacesPerDayAcrossThreeDaysWithRequiredFixedSchedule() {
        CourseAiInputDto base = input();
        List<CandidateFactDto> candidates = List.of(
                candidate("want-1", "성산일출봉"),
                candidate("normal-1", "후보 1"),
                candidate("normal-2", "후보 2"),
                candidate("normal-3", "후보 3"),
                candidate("normal-4", "후보 4"),
                candidate("normal-5", "후보 5"),
                candidate("normal-6", "후보 6"),
                candidate("normal-7", "후보 7"),
                candidate("normal-8", "후보 8"));
        CourseAiInputDto sufficientInput = copyInput(base, candidates, base.hardConstraints());
        CourseAiResultDto threePerDayResult = result(List.of(
                day("2026-08-27",
                        item("normal-1", "10:00"),
                        item("normal-2", "14:00"),
                        item("normal-3", "18:00")),
                day("2026-08-28",
                        item("want-1", "09:00"),
                        item("normal-4", "14:00"),
                        item("normal-5", "18:00")),
                day("2026-08-29",
                        item("normal-6", "10:00"),
                        item("normal-7", "14:00"),
                        item("normal-8", "18:00"))));

        assertThatCode(() -> validator.validate(sufficientInput, threePerDayResult))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsTwoPlacesWithoutDuplicatingCandidatesWhenCandidatesAreLimited() {
        CourseAiResultDto twoPlaceResult = result(List.of(
                day("2026-08-28", item("want-1", "09:00"), item("normal-1", "14:00"))));

        assertThatCode(() -> validator.validate(singleDayInput(), twoPlaceResult))
                .doesNotThrowAnyException();
    }

    @Test
    void serializesOnlyTheFinalDecisionContract() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        CourseAiResultDto result = result(List.of(day(
                "2026-08-28",
                new ItemDto("want-1", LocalTime.of(9, 0), "사용자가 고정한 필수 일정입니다."))));

        String json = objectMapper.writeValueAsString(result);

        assertThat(json).isEqualTo("{\"contractVersion\":\"2.0\",\"days\":[{"
                + "\"date\":\"2026-08-28\",\"items\":[{"
                + "\"candidateId\":\"want-1\",\"startTime\":\"09:00:00\","
                + "\"recommendationReason\":\"사용자가 고정한 필수 일정입니다.\"}]}]}");
    }

    private void assertInvalid(
            CourseAiInputDto input,
            CourseAiResultDto result,
            CourseAiValidationCode code
    ) {
        assertThatThrownBy(() -> validator.validate(input, result))
                .isInstanceOfSatisfying(CourseAiValidationException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.getCode())
                                .isEqualTo(code));
    }

    private CourseAiInputDto input() {
        PlaceConstraintDto required = new PlaceConstraintDto(
                new PlaceIdentityDto("want-1", null, null, null),
                "성산일출봉", "주소", null, null, null,
                PreferenceType.WANT, date("2026-08-28"), LocalTime.of(9, 0));
        return new CourseAiInputDto(
                "2.0",
                new TripConditionDto(date("2026-08-27"), date("2026-08-29"),
                        2,  Transport.RENTAL_CAR),
                new UserPreferencesDto(List.of(), List.of(), List.of(required), List.of(), null),
                List.of(candidate("want-1", "성산일출봉"),
                        candidate("normal-1", "만장굴"), candidate("normal-2", "비자림")),
                List.of(), null);
    }

    private CourseAiInputDto singleDayInput() {
        CourseAiInputDto base = input();
        return new CourseAiInputDto(base.contractVersion(),
                new TripConditionDto(date("2026-08-28"), date("2026-08-28"), 2,
                        Transport.RENTAL_CAR), base.userPreferences(), base.candidates(),
                base.travelFacts(), base.generationMetadata());
    }

    private CandidateFactDto candidate(String id, String name) {
        return new CandidateFactDto(
                new PlaceIdentityDto(id, null, null, null), name, "주소",
                33.4, 126.8, null, "EAST", null, List.of(), List.of(), null);
    }

    private CourseAiResultDto result(List<DayDto> days) {
        return new CourseAiResultDto("2.0", days);
    }

    private CourseAiResultDto validSingleItemResult() {
        return result(List.of(day("2026-08-28", item("want-1", "09:00"))));
    }

    private CourseAiInputDto copyInput(
            CourseAiInputDto base,
            List<CandidateFactDto> candidates,
            HardConstraintsDto hardConstraints
    ) {
        return new CourseAiInputDto(
                base.contractVersion(), base.trip(), base.preferences(), hardConstraints,
                base.accommodation(), candidates, base.weatherFactSets(), base.travelFacts(),
                base.compatibility());
    }

    private DayDto day(String date, ItemDto... items) {
        return new DayDto(date(date), Arrays.asList(items));
    }

    private ItemDto item(String candidateId, String time) {
        return new ItemDto(candidateId, LocalTime.parse(time), "입력 사실 기반 추천");
    }

    private LocalDate date(String value) {
        return LocalDate.parse(value);
    }
}
