package com.example.hangat.course;

import com.example.hangat.course.ai.CourseAiResultDto;
import com.example.hangat.course.facts.CandidateIdentity;
import com.example.hangat.course.facts.CourseCandidate;
import com.example.hangat.course.facts.CourseGenerationFacts;
import com.example.hangat.course.facts.InternalPlaceCategory;
import com.example.hangat.course.facts.PlaceFact;
import com.example.hangat.course.facts.UserConstraint;
import com.example.hangat.course.model.CourseRequestDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CourseVisitOrderOptimizerTest {
    private static final LocalDate DATE = LocalDate.of(2026, 9, 3);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void reducesSouthNorthSouthWithoutChangingPlacesDatesSlotsOrReasons() throws Exception {
        CourseGenerationFacts facts = facts(
                candidate("south-1", 33.24, 126.57, UserConstraint.none()),
                candidate("north", 33.52, 126.53, UserConstraint.none()),
                candidate("south-2", 33.25, 126.56, UserConstraint.none()));
        CourseAiResultDto original = result("south-1", "north", "south-2");

        CourseAiResultDto optimized = CourseVisitOrderOptimizer.optimize(request(), facts, original);

        assertThat(optimized.days()).hasSize(1);
        assertThat(optimized.days().get(0).date()).isEqualTo(DATE);
        assertThat(optimized.days().get(0).items()).extracting(CourseAiResultDto.ItemDto::candidateId)
                .containsExactly("south-1", "south-2", "north");
        assertThat(optimized.days().get(0).items()).extracting(CourseAiResultDto.ItemDto::startTime)
                .containsExactly(LocalTime.of(9, 0), LocalTime.of(13, 0), LocalTime.of(17, 0));
        assertThat(optimized.days().get(0).items().get(1).recommendationReason())
                .isEqualTo("reason-south-2");
    }

    @Test
    void keepsFixedTimeCandidateInItsSlotAndIsDeterministic() throws Exception {
        CourseGenerationFacts facts = facts(
                candidate("south-1", 33.24, 126.57, UserConstraint.none()),
                candidate("north", 33.52, 126.53,
                        UserConstraint.want(DATE, LocalTime.of(13, 0))),
                candidate("south-2", 33.25, 126.56, UserConstraint.none()));
        CourseAiResultDto original = result("south-1", "north", "south-2");

        CourseAiResultDto first = CourseVisitOrderOptimizer.optimize(request(), facts, original);
        CourseAiResultDto second = CourseVisitOrderOptimizer.optimize(request(), facts, original);

        assertThat(first).isEqualTo(second);
        assertThat(first.days().get(0).items().get(1).candidateId()).isEqualTo("north");
    }

    @Test
    void preservesOriginalDayWhenAnyCoordinateIsMissingOrOutsideJeju() throws Exception {
        CourseCandidate missing = candidate("missing", 33.2, 126.5, UserConstraint.none());
        missing = new CourseCandidate(missing.identity(),
                new PlaceFact("missing", null, null, null, new BigDecimal("126.5"), null),
                missing.userConstraint(), missing.regionCode(), List.of(),
                missing.internalPlaceCategory(), List.of(), List.of(), null);
        CourseGenerationFacts facts = facts(missing,
                candidate("outside", 37.5, 127.0, UserConstraint.none()));
        CourseAiResultDto original = result("missing", "outside");

        assertThat(CourseVisitOrderOptimizer.optimize(request(), facts, original)).isEqualTo(original);
    }

    @Test
    void doesNotReorderNonCarTransportOrMoveCandidatesAcrossDays() throws Exception {
        CourseRequestDto publicTransit = mapper.readValue("""
                {"start_date":"2026-09-03","end_date":"2026-09-04","people":2,
                 "budget_total":300000,"transport":"PUBLIC_TRANSIT","course_regions":[],
                 "course_styles":[{"code":"NATURE","weight":1}],"course_place_preferences":[]}
                """, CourseRequestDto.class);
        CourseGenerationFacts facts = facts(
                candidate("south", 33.24, 126.57, UserConstraint.none()),
                candidate("north", 33.52, 126.53, UserConstraint.none()));
        CourseAiResultDto original = result("south", "north");

        assertThat(CourseVisitOrderOptimizer.optimize(publicTransit, facts, original))
                .isSameAs(original);
    }

    @Test
    void keepsZeroOrOneItemDayUnchanged() throws Exception {
        CourseAiResultDto empty = new CourseAiResultDto("1.0",
                List.of(new CourseAiResultDto.DayDto(DATE, List.of())));
        CourseAiResultDto single = result("south");
        CourseGenerationFacts facts = facts(
                candidate("south", 33.24, 126.57, UserConstraint.none()));

        assertThat(CourseVisitOrderOptimizer.optimize(request(), facts, empty)).isEqualTo(empty);
        assertThat(CourseVisitOrderOptimizer.optimize(request(), facts, single)).isEqualTo(single);
    }

    @Test
    void usesAccommodationAsStartAndReturnAnchor() throws Exception {
        CourseGenerationFacts facts = facts(
                candidate("north", 33.52, 126.53, UserConstraint.none()),
                candidate("south-1", 33.24, 126.57, UserConstraint.none()),
                candidate("south-2", 33.25, 126.56, UserConstraint.none()));
        CourseAiResultDto original = result("north", "south-1", "south-2");

        CourseAiResultDto optimized = CourseVisitOrderOptimizer.optimize(
                requestWithAccommodation(), facts, original);

        assertThat(optimized.days().get(0).items())
                .extracting(CourseAiResultDto.ItemDto::candidateId)
                .containsExactly("north", "south-2", "south-1");
        assertThat(optimized.days().get(0).items())
                .extracting(CourseAiResultDto.ItemDto::startTime)
                .containsExactly(LocalTime.of(9, 0), LocalTime.of(13, 0), LocalTime.of(17, 0));
    }

    private CourseRequestDto request() throws Exception {
        return mapper.readValue("""
                {"start_date":"2026-09-03","end_date":"2026-09-03","people":2,
                 "budget_total":300000,"transport":"RENTAL_CAR","course_regions":[],
                 "course_styles":[{"code":"NATURE","weight":1}],
                 "course_place_preferences":[]}
                """, CourseRequestDto.class);
    }

    private CourseRequestDto requestWithAccommodation() throws Exception {
        return mapper.readValue("""
                {"start_date":"2026-09-03","end_date":"2026-09-03","people":2,
                 "budget_total":300000,"transport":"RENTAL_CAR","course_regions":[],
                 "course_styles":[{"code":"NATURE","weight":1}],
                 "course_place_preferences":[],"accommodation":{
                   "source_code":"KAKAO_LOCAL","source_place_id":"stay-1",
                   "place_name":"south stay","latitude":33.24,"longitude":126.57}}
                """, CourseRequestDto.class);
    }

    private CourseAiResultDto result(String... ids) {
        List<CourseAiResultDto.ItemDto> items = java.util.stream.IntStream.range(0, ids.length)
                .mapToObj(i -> new CourseAiResultDto.ItemDto(ids[i],
                        LocalTime.of(9 + i * 4, 0), "reason-" + ids[i])).toList();
        return new CourseAiResultDto("1.0", List.of(new CourseAiResultDto.DayDto(DATE, items)));
    }

    private CourseGenerationFacts facts(CourseCandidate... candidates) {
        return new CourseGenerationFacts(List.of(candidates), List.of(), List.of());
    }

    private CourseCandidate candidate(String id, double latitude, double longitude,
            UserConstraint constraint) {
        return new CourseCandidate(new CandidateIdentity(id, null, "KTO", id),
                new PlaceFact(id, null, null, BigDecimal.valueOf(latitude),
                        BigDecimal.valueOf(longitude), null), constraint, "JEJU", List.of(),
                new InternalPlaceCategory(null, "TOURIST", "관광지"), List.of(), List.of(), null);
    }
}
