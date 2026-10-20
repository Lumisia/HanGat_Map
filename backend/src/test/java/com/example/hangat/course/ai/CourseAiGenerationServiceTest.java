package com.example.hangat.course.ai;

import com.example.hangat.course.ai.CourseAiInputDto.CandidateFactDto;
import com.example.hangat.course.ai.CourseAiInputDto.PlaceIdentityDto;
import com.example.hangat.course.ai.CourseAiInputDto.TripConditionDto;
import com.example.hangat.course.ai.CourseAiInputDto.UserPreferencesDto;
import com.example.hangat.course.ai.CourseAiResultDto.DayDto;
import com.example.hangat.course.ai.CourseAiResultDto.ItemDto;
import com.example.hangat.course.model.PreferenceType;
import com.example.hangat.course.model.Transport;
import com.example.hangat.course.model.GenerationReason;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CourseAiGenerationServiceTest {

    @Test
    void invokesProviderThenValidator() {
        AtomicBoolean providerCalled = new AtomicBoolean();
        CourseAiInputDto input = fallbackInput();
        CourseAiResultDto result = new CourseAiResultDto("1.0", List.of(
                new DayDto(LocalDate.parse("2026-08-28"), List.of(
                        new ItemDto("want-1", LocalTime.parse("09:00"), "입력 근거")))));
        CourseAiGenerationService service = new CourseAiGenerationService(
                actualInput -> {
                    assertThat(actualInput).isSameAs(input);
                    providerCalled.set(true);
                    return result;
                },
                new CourseAiResultValidator());

        assertThat(service.generate(input)).isSameAs(result);
        assertThat(providerCalled).isTrue();
    }

    @Test
    void correctsOneValidationFailureAndReturnsValidResult() {
        CourseAiInputDto input = fallbackInput();
        CourseAiResultDto duplicate = duplicateResult();
        CourseAiResultDto corrected = validResult();
        AtomicInteger calls = new AtomicInteger();
        CourseAiProvider provider = new CourseAiProvider() {
            @Override
            public CourseAiResultDto generate(CourseAiInputDto actualInput) {
                calls.incrementAndGet();
                return duplicate;
            }

            @Override
            public CourseAiResultDto generateCorrection(
                    CourseAiInputDto actualInput,
                    CourseAiResultDto previousResult,
                    CourseAiValidationCode validationCode,
                    String validationMessage
            ) {
                calls.incrementAndGet();
                assertThat(actualInput).isSameAs(input);
                assertThat(previousResult).isSameAs(duplicate);
                assertThat(validationCode)
                        .isEqualTo(CourseAiValidationCode.AI_RESULT_DUPLICATE_CANDIDATE);
                assertThat(validationMessage).contains("중복 배치");
                return corrected;
            }
        };
        CourseAiGenerationService service = new CourseAiGenerationService(
                provider, new CourseAiResultValidator());

        assertThat(service.generate(input)).isSameAs(corrected);
        assertThat(calls).hasValue(2);
    }

    @Test
    void failsAfterOneCorrectionWhenCorrectedResultIsStillInvalid() {
        CourseAiInputDto input = input();
        AtomicInteger calls = new AtomicInteger();
        CourseAiProvider provider = new CourseAiProvider() {
            @Override
            public CourseAiResultDto generate(CourseAiInputDto actualInput) {
                calls.incrementAndGet();
                return duplicateResult();
            }

            @Override
            public CourseAiResultDto generateCorrection(
                    CourseAiInputDto actualInput,
                    CourseAiResultDto previousResult,
                    CourseAiValidationCode validationCode,
                    String validationMessage
            ) {
                calls.incrementAndGet();
                return duplicateResult();
            }
        };
        CourseAiGenerationService service = new CourseAiGenerationService(
                provider, new CourseAiResultValidator());

        assertThatThrownBy(() -> service.generate(input))
                .isInstanceOfSatisfying(CourseAiException.class, exception ->
                    assertThat(exception.getFailureType()).isEqualTo(CourseAiFailureType.TEMPORARILY_UNAVAILABLE));
        assertThat(calls).hasValue(2);
    }

    @Test
    void correctsOneWireTimeFormatFailureAndReturnsValidResult() {
        CourseAiInputDto input = fallbackInput();
        CourseAiResultDto corrected = validResult();
        AtomicInteger calls = new AtomicInteger();
        CourseAiProvider provider = new CourseAiProvider() {
            @Override
            public CourseAiResultDto generate(CourseAiInputDto actualInput) {
                calls.incrementAndGet();
                throw invalidTimeFormat();
            }

            @Override
            public CourseAiResultDto generateCorrection(
                    CourseAiInputDto actualInput,
                    CourseAiResultDto previousResult,
                    CourseAiValidationCode validationCode,
                    String validationMessage
            ) {
                calls.incrementAndGet();
                assertThat(actualInput).isSameAs(input);
                assertThat(previousResult).isNull();
                assertThat(validationCode)
                        .isEqualTo(CourseAiValidationCode.AI_RESULT_START_TIME_FORMAT_INVALID);
                assertThat(validationMessage).contains("제주 현지 시각 HH:mm:ss");
                return corrected;
            }
        };
        CourseAiGenerationService service = new CourseAiGenerationService(
                provider, new CourseAiResultValidator());

        assertThat(service.generate(input)).isSameAs(corrected);
        assertThat(calls).hasValue(2);
    }

    @Test
    void failsAfterOneCorrectionWhenCorrectedWireTimeIsStillInvalid() {
        AtomicInteger calls = new AtomicInteger();
        CourseAiProvider provider = new CourseAiProvider() {
            @Override
            public CourseAiResultDto generate(CourseAiInputDto actualInput) {
                calls.incrementAndGet();
                throw invalidTimeFormat();
            }

            @Override
            public CourseAiResultDto generateCorrection(
                    CourseAiInputDto actualInput,
                    CourseAiResultDto previousResult,
                    CourseAiValidationCode validationCode,
                    String validationMessage
            ) {
                calls.incrementAndGet();
                throw invalidTimeFormat();
            }
        };
        CourseAiGenerationService service = new CourseAiGenerationService(
                provider, new CourseAiResultValidator());

        assertThatThrownBy(() -> service.generate(input()))
                .isInstanceOfSatisfying(CourseAiException.class, exception ->
                        assertThat(exception.getFailureType()).isEqualTo(CourseAiFailureType.TEMPORARILY_UNAVAILABLE));
        assertThat(calls).hasValue(2);
    }

    @Test
    void usesDeterministicFallbackAfterProviderFailure() {
        AtomicInteger calls = new AtomicInteger();
        CourseAiGenerationService service = new CourseAiGenerationService(
                actualInput -> {
                    calls.incrementAndGet();
                    throw new CourseAiException(
                            CourseAiFailureType.PROVIDER_ERROR,
                            "Gemini HTTP 503 failure");
                },
                new CourseAiResultValidator());

        assertThat(service.generate(fallbackInput()).days()).hasSize(1);
        assertThat(calls).hasValue(1);
    }

    @Test
    void usesDeterministicFallbackAfterNetworkFailure() {
        AtomicInteger calls = new AtomicInteger();
        CourseAiGenerationService service = new CourseAiGenerationService(
                actualInput -> {
                    calls.incrementAndGet();
                    throw new CourseAiException(
                            CourseAiFailureType.PROVIDER_ERROR,
                            "Gemini network timeout");
                },
                new CourseAiResultValidator());

        assertThat(service.generate(fallbackInput()).days()).hasSize(1);
        assertThat(calls).hasValue(1);
    }

    @Test
    void usesDeterministicFallbackAfterRateLimitFailure() {
        AtomicInteger calls = new AtomicInteger();
        CourseAiGenerationService service = new CourseAiGenerationService(
                actualInput -> {
                    calls.incrementAndGet();
                    throw new CourseAiException(
                            CourseAiFailureType.RATE_LIMIT,
                            "Gemini rate limit");
                },
                new CourseAiResultValidator());

        assertThat(service.generate(fallbackInput()).days()).hasSize(1);
        assertThat(calls).hasValue(1);
    }

    private CourseAiResultDto validResult() {
        return new CourseAiResultDto("1.0", List.of(
                new DayDto(LocalDate.parse("2026-08-28"), List.of(
                        new ItemDto("want-1", LocalTime.parse("09:00"), "입력 근거")))));
    }

    private CourseAiResultDto duplicateResult() {
        return new CourseAiResultDto("1.0", List.of(
                new DayDto(LocalDate.parse("2026-08-28"), List.of(
                        new ItemDto("want-1", LocalTime.parse("09:00"), "입력 근거"),
                        new ItemDto("want-1", LocalTime.parse("11:00"), "중복 근거")))));
    }

    private CourseAiValidationException invalidTimeFormat() {
        return new CourseAiValidationException(
                CourseAiValidationCode.AI_RESULT_START_TIME_FORMAT_INVALID,
                "AI 코스 결과의 startTime은 제주 현지 시각 HH:mm:ss 형식이어야 합니다.");
    }

    private CourseAiInputDto input() {
        CandidateFactDto want = new CandidateFactDto(
                new PlaceIdentityDto("want-1", null, null, null),
                "성산일출봉", "주소", 33.4, 126.9, null, "EAST",
                PreferenceType.WANT, List.of(), List.of(), null);
        return new CourseAiInputDto(
                "1.0",
                new TripConditionDto(
                        LocalDate.parse("2026-08-27"), LocalDate.parse("2026-08-29"),
                        2,  Transport.RENTAL_CAR),
                new UserPreferencesDto(List.of(), List.of(), List.of(), List.of(), null),
                List.of(want), List.of(), null);
    }

    @Test
    void deterministicRegenerationUsesAStableDifferentOrderAndNeverInventsCandidates() {
        CourseAiProvider unavailable = actualInput -> {
            throw new CourseAiException(CourseAiFailureType.PROVIDER_ERROR, "provider unavailable");
        };
        CourseAiGenerationService service = new CourseAiGenerationService(
                unavailable, new CourseAiResultValidator());

        CourseAiResultDto initial = service.generate(multiCandidateInput(GenerationReason.INITIAL));
        CourseAiResultDto regenerated = service.generate(multiCandidateInput(GenerationReason.USER_REGENERATE));

        assertThat(initial.days().get(0).items()).extracting(ItemDto::candidateId)
                .containsExactly("candidate-a", "candidate-b", "candidate-c");
        assertThat(regenerated.days().get(0).items()).extracting(ItemDto::candidateId)
                .containsExactly("candidate-c", "candidate-b", "candidate-a");
    }

    private CourseAiInputDto fallbackInput() {
        CourseAiInputDto original = input();
        return new CourseAiInputDto(original.contractVersion(),
                new TripConditionDto(LocalDate.parse("2026-08-28"), LocalDate.parse("2026-08-28"),
                        2,  Transport.RENTAL_CAR),
                original.userPreferences(), original.candidates(), original.travelFacts(), original.generationMetadata());
    }

    private CourseAiInputDto multiCandidateInput(GenerationReason reason) {
        List<CandidateFactDto> candidates = List.of(
                candidate("candidate-a"), candidate("candidate-b"), candidate("candidate-c"));
        return new CourseAiInputDto("1.0",
                new TripConditionDto(LocalDate.parse("2026-08-28"), LocalDate.parse("2026-08-28"),
                        2,  Transport.PUBLIC_TRANSIT),
                new UserPreferencesDto(List.of(), List.of(), List.of(), List.of(), null),
                candidates, List.of(), new CourseAiInputDto.GenerationMetadataDto(reason, null, null));
    }

    private CandidateFactDto candidate(String id) {
        return new CandidateFactDto(new PlaceIdentityDto(id, null, "KTO", id), id, "주소",
                33.4, 126.6, null, "EAST", null, List.of(), List.of(), null);
    }
}
