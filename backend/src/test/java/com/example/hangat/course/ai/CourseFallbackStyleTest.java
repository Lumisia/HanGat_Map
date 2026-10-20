package com.example.hangat.course.ai;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import static com.example.hangat.course.ai.CourseAiInputDto.*;
import static org.assertj.core.api.Assertions.*;
import com.example.hangat.course.model.Transport;

class CourseFallbackStyleTest {
    private final DeterministicCourseFallback fallback = new DeterministicCourseFallback();

    @Test void reservesCafeInsteadOfFillingEverySlotWithAnotherSelectedStyle() {
        var input=input(List.of("NATURE","CAFE"),List.of(
                candidate("a","TOURIST","NATURE"),candidate("b","TOURIST","NATURE"),
                candidate("c","TOURIST","NATURE"),candidate("z","CAFE")),List.of());
        var result=fallback.generate(input);
        assertThat(result.days().get(0).items()).extracting(CourseAiResultDto.ItemDto::candidateId).contains("z");
        assertThatCode(()->new CourseAiResultValidator().validate(input,result)).doesNotThrowAnyException();
    }

    @Test void coversDifferentAvailableStylesBeforeRepeatingOne() {
        var result=fallback.generate(input(List.of("LOCAL","PHOTO"),List.of(
                candidate("a","TOURIST","LOCAL"),candidate("b","TOURIST","LOCAL"),
                candidate("c","TOURIST","LOCAL"),candidate("z","TOURIST","PHOTO")),List.of()));
        assertThat(result.days().get(0).items()).extracting(CourseAiResultDto.ItemDto::candidateId).contains("z");
    }

    @Test void doesNotScheduleAnImpossibleWalkingLegAndCanUseAnotherCandidate() {
        var input=input(List.of(),List.of(candidate("a","TOURIST"),candidate("b","TOURIST"),candidate("c","TOURIST")),
                List.of(new TravelFactDto("a","b",null,null,900,Transport.WALK_BIKE,null),
                        new TravelFactDto("c","b",null,null,900,Transport.WALK_BIKE,null)));
        var result=fallback.generate(input);
        assertThatCode(()->new CourseAiResultValidator().validate(input,result)).doesNotThrowAnyException();
        assertThat(result.days().get(0).items()).extracting(CourseAiResultDto.ItemDto::candidateId).contains("c").doesNotContain("b");
    }

    @Test void validatorRejectsTravelThatWrapsPastMidnight() {
        var input=input(List.of(),List.of(candidate("a","TOURIST"),candidate("b","TOURIST")),
                List.of(new TravelFactDto("a","b",null,null,900,Transport.WALK_BIKE,null)));
        var result=new CourseAiResultDto("1.0",List.of(new CourseAiResultDto.DayDto(LocalDate.of(2026,9,18),List.of(
                new CourseAiResultDto.ItemDto("a",LocalTime.of(10,0),"reason"),
                new CourseAiResultDto.ItemDto("b",LocalTime.of(14,0),"reason")))));
        assertThatThrownBy(()->new CourseAiResultValidator().validate(input,result))
                .isInstanceOf(CourseAiValidationException.class);
    }

    @Test void softStyleCoverageDoesNotOverrideQuieterAlternativePolicy() {
        var quiet=com.example.hangat.map.model.enums.CongestionLevel.QUIET;
        var crowded=com.example.hangat.map.model.enums.CongestionLevel.CROWDED;
        var candidates=List.of(withCongestion(candidate("a","TOURIST","LOCAL"),quiet),
                withCongestion(candidate("b","TOURIST","LOCAL"),quiet),
                withCongestion(candidate("c","TOURIST","LOCAL"),quiet),
                withCongestion(candidate("z","TOURIST","PHOTO"),crowded));
        var input=input(List.of("LOCAL","PHOTO"),candidates,List.of());
        var result=fallback.generate(input);
        assertThatCode(()->new CourseAiResultValidator().validate(input,result)).doesNotThrowAnyException();
    }

    @Test void retriesCafeAfterAllQuieterAlternativesHaveBeenScheduled() {
        var quiet=com.example.hangat.map.model.enums.CongestionLevel.QUIET;
        var crowded=com.example.hangat.map.model.enums.CongestionLevel.CROWDED;
        var input=input(List.of("CAFE"),List.of(withCongestion(candidate("z","CAFE"),crowded),
                withCongestion(candidate("a","TOURIST"),quiet),withCongestion(candidate("b","TOURIST"),quiet)),List.of());
        var result=fallback.generate(input);
        assertThatCode(()->new CourseAiResultValidator().validate(input,result)).doesNotThrowAnyException();
        assertThat(result.days().get(0).items()).extracting(CourseAiResultDto.ItemDto::candidateId).contains("z");
    }

    private CandidateFactDto withCongestion(CandidateFactDto candidate,com.example.hangat.map.model.enums.CongestionLevel level) {
        return new CandidateFactDto(candidate.candidateId(),candidate.name(),candidate.regionCode(),candidate.internalCategoryCode(),
                candidate.styleHintCodes(),List.of(new CongestionFactDto(LocalDate.of(2026,9,18),null,level)),null,null);
    }

    @Test void crowdedStyleCanUseAnotherDateWhereItIsQuiet() {
        var start=LocalDate.of(2026,9,18);
        var quiet=com.example.hangat.map.model.enums.CongestionLevel.QUIET;
        var crowded=com.example.hangat.map.model.enums.CongestionLevel.CROWDED;
        var photo=new CandidateFactDto("z","z","EAST","TOURIST",List.of("PHOTO"),List.of(
                new CongestionFactDto(start,null,crowded),new CongestionFactDto(start.plusDays(1),null,quiet)),null,null);
        var input=new CourseAiInputDto("1.0",new TripConstraintDto(start,start.plusDays(1),Transport.WALK_BIKE),
                new SoftPreferencesDto(List.of(),List.of("PHOTO")),new HardConstraintsDto(List.of()),null,
                List.of(withCongestion(candidate("a","TOURIST"),quiet),photo),List.of(),List.of(),null);
        var result=fallback.generate(input);
        assertThatCode(()->new CourseAiResultValidator().validate(input,result)).doesNotThrowAnyException();
        assertThat(result.days().get(1).items()).extracting(CourseAiResultDto.ItemDto::candidateId).contains("z");
    }

    @Test void fixedWantKeepsExistingExceptionToCongestionPolicy() {
        var start=LocalDate.of(2026,9,18);
        var crowded=com.example.hangat.map.model.enums.CongestionLevel.CROWDED;
        var quiet=com.example.hangat.map.model.enums.CongestionLevel.QUIET;
        var input=new CourseAiInputDto("1.0",new TripConstraintDto(start,start,Transport.WALK_BIKE),
                new SoftPreferencesDto(List.of(),List.of("PHOTO")),
                new HardConstraintsDto(List.of(new RequiredCandidateConstraintDto("z",start,LocalTime.of(14,0)))),null,
                List.of(withCongestion(candidate("z","TOURIST","PHOTO"),crowded),
                        withCongestion(candidate("a","TOURIST"),quiet),withCongestion(candidate("b","TOURIST"),quiet),
                        withCongestion(candidate("c","TOURIST"),quiet)),List.of(),List.of(),null);
        var result=fallback.generate(input);
        assertThatCode(()->new CourseAiResultValidator().validate(input,result)).doesNotThrowAnyException();
        assertThat(result.days().get(0).items()).anySatisfy(item -> {
            assertThat(item.candidateId()).isEqualTo("z");
            assertThat(item.startTime()).isEqualTo(LocalTime.of(14,0));
        });
    }

    static CandidateFactDto candidate(String id,String category,String...styles) {
        return new CandidateFactDto(id,id,"EAST",category,List.of(styles),List.of(),null,null);
    }
    static CourseAiInputDto input(List<String> styles,List<CandidateFactDto> candidates,List<TravelFactDto> travel) {
        return new CourseAiInputDto("1.0",new TripConstraintDto(LocalDate.of(2026,9,18),LocalDate.of(2026,9,18),Transport.WALK_BIKE),
                new SoftPreferencesDto(List.of(),styles),new HardConstraintsDto(List.of()),null,candidates,List.of(),travel,null);
    }
}
