package com.example.hangat.course;

import com.example.hangat.course.ai.CourseAiResultDto;
import com.example.hangat.course.facts.CandidateIdentity;
import com.example.hangat.course.facts.CongestionFact;
import com.example.hangat.course.facts.CourseCandidate;
import com.example.hangat.course.facts.CourseGenerationFacts;
import com.example.hangat.course.facts.ExternalClassificationFact;
import com.example.hangat.course.facts.InternalPlaceCategory;
import com.example.hangat.course.facts.PlaceFact;
import com.example.hangat.course.facts.StyleHint;
import com.example.hangat.course.facts.TravelFact;
import com.example.hangat.course.facts.UserConstraint;
import com.example.hangat.course.facts.WeatherFact;
import com.example.hangat.course.facts.WeatherFactSet;
import com.example.hangat.course.model.AccommodationDto;
import com.example.hangat.map.model.enums.CongestionLevel;
import com.example.hangat.course.model.CourseResponseDto;
import com.example.hangat.course.model.PreferenceType;
import com.example.hangat.course.model.entity.Course;
import com.example.hangat.course.model.entity.CourseItem;
import com.example.hangat.course.model.enums.CourseItemSource;
import com.example.hangat.course.model.enums.CourseStatus;
import com.example.hangat.course.model.enums.CourseType;
import com.example.hangat.course.model.enums.GenerationReason;
import com.example.hangat.course.model.Transport;
import com.example.hangat.map.model.entity.Place;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CourseResponseAssemblerTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());
    private final CourseResponseAssembler assembler = new CourseResponseAssembler();

    @Test
    void assemblesProviderNeutralFactsWithPersistedIdsAndOrdering() throws Exception {
        LocalDate visitDate = LocalDate.of(2026, 9, 10);
        CourseCandidate kto = candidate(
                "kto-1", "KTO", "125266", "비자림", "제주 구좌읍",
                "제주특별자치도 제주시 구좌읍 비자숲길 55", "forest.jpg",
                null, "TOURIST", "관광지", List.of("NATURE"),
                List.of(
                        congestion(visitDate, "42.50", CongestionLevel.NORMAL),
                        congestion(visitDate.plusDays(1), "70.00", CongestionLevel.CROWDED)),
                "weather-east");
        CourseCandidate kakao = candidate(
                "kakao-1", "KAKAO_LOCAL", "987654", "사용자 카페", "제주 주소",
                null, null, PreferenceType.WANT, "CAFE", "카페", List.of("CAFE"),
                List.of(), "weather-east");
        CourseGenerationFacts facts = new CourseGenerationFacts(
                List.of(kto, kakao),
                List.of(weatherSet("weather-east", visitDate)),
                List.of(new TravelFact(
                        "kto-1", "kakao-1", new BigDecimal("8200"), "HAVERSINE",
                        null, null, Transport.RENTAL_CAR, null, null)));
        CourseAiResultDto result = new CourseAiResultDto("2.0", List.of(
                day(visitDate,
                        item("kto-1", "09:00", "숲길 추천"),
                        item("kakao-1", "11:00", "사용자 선택 유지"))));
        CoursePersistenceResult persistence = persistence(
                List.of("kto-1", "kakao-1"),
                Map.of("kto-1", CourseItemSource.AI_RECOMMENDED,
                        "kakao-1", CourseItemSource.USER_FIXED),
                Map.of("kto-1", "관광지", "kakao-1", "카페"),
                visitDate);

        CourseResponseDto response = assembler.assemble(
                facts, result, persistence, accommodation());

        assertThat(response.id()).isEqualTo(101L);
        assertThat(response.contractVersion()).isEqualTo("2.0");
        assertThat(response.averageCongestionRate()).isEqualByComparingTo("33.00");
        assertThat(response.days()).singleElement().satisfies(day -> {
            assertThat(day.dayNo()).isEqualTo(1);
            assertThat(day.visitDate()).isEqualTo(visitDate);
            assertThat(day.items()).extracting(CourseResponseDto.ItemDto::candidateId)
                    .containsExactly("kto-1", "kakao-1");
        });

        CourseResponseDto.ItemDto ktoItem = response.days().get(0).items().get(0);
        assertThat(ktoItem.id()).isEqualTo(201L);
        assertThat(ktoItem.placeId()).isEqualTo(301L);
        assertThat(ktoItem.sourceCode()).isEqualTo("KTO");
        assertThat(ktoItem.sourcePlaceId()).isEqualTo("125266");
        assertThat(ktoItem.placeName()).isEqualTo("비자림");
        assertThat(ktoItem.address()).isEqualTo("제주 구좌읍");
        assertThat(ktoItem.roadAddress())
                .isEqualTo("제주특별자치도 제주시 구좌읍 비자숲길 55");
        assertThat(ktoItem.imageUrl()).isEqualTo("forest.jpg");
        assertThat(ktoItem.categoryName()).isEqualTo("관광지");
        assertThat(ktoItem.confirmedStyleHints()).containsExactly("NATURE");
        assertThat(ktoItem.itemSource())
                .isEqualTo(CourseResponseDto.ItemSource.AI_RECOMMENDED);
        assertThat(ktoItem.congestion()).hasSize(2);
        assertThat(ktoItem.congestionRate()).isEqualByComparingTo("42.50");
        assertThat(ktoItem.congestionLevel()).isEqualTo(CongestionLevel.NORMAL);
        assertThat(ktoItem.weather()).hasSize(2);
        assertThat(ktoItem.inboundDistanceM()).isNull();
        assertThat(ktoItem.inboundTravelMinutes()).isNull();

        CourseResponseDto.ItemDto kakaoItem = response.days().get(0).items().get(1);
        assertThat(kakaoItem.id()).isEqualTo(202L);
        assertThat(kakaoItem.placeId()).isEqualTo(302L);
        assertThat(kakaoItem.sourceCode()).isEqualTo("KAKAO_LOCAL");
        assertThat(kakaoItem.sourcePlaceId()).isEqualTo("987654");
        assertThat(kakaoItem.imageUrl()).isNull();
        assertThat(kakaoItem.itemSource())
                .isEqualTo(CourseResponseDto.ItemSource.USER_FIXED);
        assertThat(kakaoItem.congestion()).isEmpty();
        assertThat(kakaoItem.congestionRate()).isNull();
        assertThat(kakaoItem.congestionLevel()).isNull();
        assertThat(kakaoItem.inboundDistanceM()).isNull();
        assertThat(kakaoItem.inboundTravelMinutes()).isNull();
        assertThat(kakaoItem.costs()).isEmpty();
        assertThat(kakaoItem.weather()).hasSize(2);
        assertThat(response.accommodation()).isNotNull();
    }

    @Test
    void usesOnlyRealRouteFactsForInboundTravel() {
        LocalDate visitDate = LocalDate.of(2026, 9, 10);
        CourseCandidate first = candidate(
                "first", "KTO", "1", "첫 장소", null, null, null,
                null, "TOURIST", "관광지", List.of(), List.of(), null);
        CourseCandidate second = withConstraint(candidate(
                "second", "KTO", "2", "둘째 장소", null, null, null,
                null, "TOURIST", "관광지", List.of(), List.of(), null),
                UserConstraint.want(null, null));
        CourseGenerationFacts facts = new CourseGenerationFacts(
                List.of(first, second), List.of(), List.of(new TravelFact(
                        "first", "second", new BigDecimal("1500"), "HAVERSINE",
                        new BigDecimal("2100"), 8, Transport.RENTAL_CAR,
                        "ROUTE_PROVIDER", Instant.parse("2026-09-01T00:00:00Z"))));
        CoursePersistenceResult persistence = persistence(
                List.of("first", "second"),
                Map.of("first", CourseItemSource.AI_RECOMMENDED,
                        "second", CourseItemSource.AI_RECOMMENDED),
                Map.of("first", "관광지", "second", "관광지"),
                visitDate);

        CourseResponseDto response = assembler.assemble(
                facts,
                new CourseAiResultDto("2.0", List.of(day(
                        visitDate, item("first", "09:00", "첫 추천"),
                        item("second", "11:00", "둘째 추천")))),
                persistence,
                null);

        assertThat(response.days().get(0).items().get(0).inboundDistanceM()).isNull();
        assertThat(response.days().get(0).items().get(1).inboundDistanceM())
                .isEqualByComparingTo("2100");
        assertThat(response.days().get(0).items().get(1).inboundTravelMinutes())
                .isEqualTo(8);
        assertThat(response.days().get(0).items().get(1).preferenceType())
                .isEqualTo(PreferenceType.WANT);
        assertThat(response.days().get(0).items().get(1).itemSource())
                .isEqualTo(CourseResponseDto.ItemSource.AI_RECOMMENDED);
    }

    @Test
    void rejectsUnknownCandidateAndMissingPersistedItem() {
        LocalDate visitDate = LocalDate.of(2026, 9, 10);
        CourseCandidate known = candidate(
                "known", "KTO", "1", "장소", null, null, null,
                null, "TOURIST", "관광지", List.of(), List.of(), null);
        CourseGenerationFacts facts = new CourseGenerationFacts(
                List.of(known), List.of(), List.of());
        CoursePersistenceResult persistence = persistence(
                List.of("known"),
                Map.of("known", CourseItemSource.AI_RECOMMENDED),
                Map.of("known", "관광지"),
                visitDate);

        assertThatThrownBy(() -> assembler.assemble(
                facts,
                new CourseAiResultDto("2.0", List.of(day(
                        visitDate, item("unknown", "09:00", "추천")))),
                persistence,
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown");

        CoursePersistenceResult missingItem = new CoursePersistenceResult(
                persistence.course(), Map.of(), Map.of());
        assertThatThrownBy(() -> assembler.assemble(
                facts,
                new CourseAiResultDto("2.0", List.of(day(
                        visitDate, item("known", "09:00", "추천")))),
                missingItem,
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("저장 항목");
    }

    @Test
    void serializesSnakeCaseProvenanceNullsAndEmptyCosts() throws Exception {
        LocalDate visitDate = LocalDate.of(2026, 9, 10);
        CourseCandidate candidate = candidate(
                "kakao-1", "KAKAO_LOCAL", "987654", "사용자 카페",
                "제주 주소", null, null, PreferenceType.WANT,
                "CAFE", "카페", List.of("CAFE"), List.of(), null);
        CoursePersistenceResult persistence = persistence(
                List.of("kakao-1"),
                Map.of("kakao-1", CourseItemSource.USER_FIXED),
                Map.of("kakao-1", "카페"),
                visitDate);
        CourseResponseDto response = assembler.assemble(
                new CourseGenerationFacts(List.of(candidate), List.of(), List.of()),
                new CourseAiResultDto("2.0", List.of(day(
                        visitDate, item("kakao-1", "11:00", "사용자 선택 유지")))),
                persistence,
                null);

        String json = objectMapper.writeValueAsString(response);
        assertThat(json).contains(
                "\"source_code\":\"KAKAO_LOCAL\"",
                "\"source_place_id\":\"987654\"",
                "\"road_address\":null",
                "\"inbound_distance_m\":null",
                "\"inbound_travel_minutes\":null",
                "\"weather\":null",
                "\"costs\":[]",
                "\"has_cost_data\":false",
                "\"total_expected\":null");
        assertThat(json).doesNotContain("tour_category", "compatibility", "budget_total", "over_budget", "remaining_budget", "usage_rate");
    }

    @Test
    void projectsBudgetSummaryAndOnlyRealItemCostFacts() {
        LocalDate visitDate = LocalDate.of(2026, 9, 10);
        CourseCandidate candidate = candidate(
                "known", "KTO", "1", "장소", null, null, null,
                null, "TOURIST", "관광지", List.of(), List.of(), null);
        CoursePersistenceResult persistence = persistence(
                List.of("known"),
                Map.of("known", CourseItemSource.AI_RECOMMENDED),
                Map.of("known", "관광지"),
                visitDate);
        CourseBudgetCalculation budget = new CourseBudgetCalculation(
                new CourseBudgetCalculation.BudgetSummary(
                        true, 16000, 120000, 80000, 120000, 136000, 0),
                96000,
                136000,
                Map.of(201L, List.of(new CourseBudgetCalculation.CostLine(
                        501L, 101L, 201L, "FOOD", "VERIFIED",
                        16000, 16000, "KRW", "8,000원 × 2명"))));

        CourseResponseDto response = assembler.assemble(
                new CourseGenerationFacts(List.of(candidate), List.of(), List.of()),
                new CourseAiResultDto("2.0", List.of(day(
                        visitDate, item("known", "09:00", "추천")))),
                persistence,
                null,
                budget);

        assertThat(response.estimatedCostMin()).isEqualTo(96000);
        assertThat(response.estimatedCostMax()).isEqualTo(136000);
        assertThat(response.budgetSummary().totalExpected()).isEqualTo(136000);
        assertThat(response.days().get(0).items().get(0).costs()).singleElement()
                .satisfies(cost -> {
                    assertThat(cost.id()).isEqualTo(501L);
                    assertThat(cost.accuracyType()).isEqualTo("VERIFIED");
                    assertThat(cost.amountMin()).isEqualByComparingTo("16000");
                });
    }

    @Test
    void rejectsMissingSharedWeatherFactSet() {
        LocalDate visitDate = LocalDate.of(2026, 9, 10);
        CourseCandidate candidate = candidate(
                "known", "KTO", "1", "장소", null, null, null,
                null, "TOURIST", "관광지", List.of(), List.of(), "missing-weather");
        CoursePersistenceResult persistence = persistence(
                List.of("known"),
                Map.of("known", CourseItemSource.AI_RECOMMENDED),
                Map.of("known", "관광지"),
                visitDate);

        assertThatThrownBy(() -> assembler.assemble(
                new CourseGenerationFacts(List.of(candidate), List.of(), List.of()),
                new CourseAiResultDto("2.0", List.of(day(
                        visitDate, item("known", "09:00", "추천")))),
                persistence,
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing-weather");
    }

    private CourseCandidate candidate(
            String candidateId,
            String sourceCode,
            String sourcePlaceId,
            String name,
            String address,
            String roadAddress,
            String imageUrl,
            PreferenceType preferenceType,
            String categoryCode,
            String categoryName,
            List<String> styleCodes,
            List<CongestionFact> congestionFacts,
            String weatherFactSetId
    ) {
        return new CourseCandidate(
                new CandidateIdentity(candidateId, null, sourceCode, sourcePlaceId),
                new PlaceFact(name, address, roadAddress,
                        new BigDecimal("33.4913"), new BigDecimal("126.8114"), imageUrl),
                preferenceType == PreferenceType.WANT
                        ? UserConstraint.want(LocalDate.of(2026, 9, 10), LocalTime.of(11, 0))
                        : UserConstraint.none(),
                "EAST",
                List.of(new ExternalClassificationFact(
                        sourceCode,
                        "KTO".equals(sourceCode) ? "A01" : null,
                        "KTO".equals(sourceCode) ? "A0101" : null,
                        "KTO".equals(sourceCode) ? "A01010100" : null,
                        "KAKAO_LOCAL".equals(sourceCode) ? "여행 > 카페" : null)),
                new InternalPlaceCategory(null, categoryCode, categoryName),
                styleCodes.stream()
                        .map(code -> new StyleHint(code, "TEST", code))
                        .toList(),
                congestionFacts,
                weatherFactSetId);
    }

    private CongestionFact congestion(
            LocalDate date,
            String rate,
            CongestionLevel level
    ) {
        return new CongestionFact(null, date, new BigDecimal(rate), level, "DATA_GO_KR");
    }

    private CourseCandidate withConstraint(
            CourseCandidate candidate,
            UserConstraint constraint
    ) {
        return new CourseCandidate(
                candidate.identity(),
                candidate.place(),
                constraint,
                candidate.regionCode(),
                candidate.externalClassifications(),
                candidate.internalPlaceCategory(),
                candidate.styleHints(),
                candidate.congestionFacts(),
                candidate.weatherFactSetId());
    }

    private WeatherFactSet weatherSet(String id, LocalDate date) {
        return new WeatherFactSet(
                id, "KMA", 52, 38, date.minusDays(1), LocalTime.of(23, 0),
                List.of(
                        new WeatherFact(null, date, LocalTime.of(9, 0),
                                new BigDecimal("27.5"), 20, "0", "1",
                                new BigDecimal("2.1"), 65),
                        new WeatherFact(null, date, LocalTime.of(12, 0),
                                new BigDecimal("29.0"), 30, "0", "3",
                                new BigDecimal("2.5"), 60),
                        new WeatherFact(null, date.plusDays(1), LocalTime.of(9, 0),
                                new BigDecimal("26.0"), 60, "1", "4",
                                new BigDecimal("3.0"), 70)));
    }

    private CoursePersistenceResult persistence(
            List<String> candidateIds,
            Map<String, CourseItemSource> sources,
            Map<String, String> categories,
            LocalDate visitDate
    ) {
        Course course = mock(Course.class);
        when(course.getId()).thenReturn(101L);
        when(course.getCourseType()).thenReturn(CourseType.USER);
        when(course.getGenerationReason()).thenReturn(GenerationReason.INITIAL);
        when(course.getStatus()).thenReturn(CourseStatus.READY);
        when(course.getStartDate()).thenReturn(visitDate);
        when(course.getEndDate()).thenReturn(visitDate.plusDays(2));
        when(course.getPeople()).thenReturn((short) 2);
        when(course.getTransport()).thenReturn(
                com.example.hangat.course.model.enums.Transport.RENTAL_CAR);
        when(course.getAverageCongestionRate()).thenReturn(new BigDecimal("33.00"));

        Map<String, CourseItem> items = new LinkedHashMap<>();
        for (int index = 0; index < candidateIds.size(); index++) {
            String candidateId = candidateIds.get(index);
            Place place = mock(Place.class);
            when(place.getId()).thenReturn(301L + index);
            CourseItem item = mock(CourseItem.class);
            when(item.getId()).thenReturn(201L + index);
            when(item.getCourse()).thenReturn(course);
            when(item.getPlace()).thenReturn(place);
            when(item.getDayNo()).thenReturn((short) 1);
            when(item.getPosition()).thenReturn((short) (index + 1));
            when(item.getVisitDate()).thenReturn(visitDate);
            when(item.getStartTime()).thenReturn(LocalTime.of(9 + index * 2, 0));
            when(item.getItemSource()).thenReturn(sources.get(candidateId));
            when(item.getInboundDistanceM()).thenReturn(null);
            when(item.getInboundTravelMinutes()).thenReturn(null);
            when(item.getRecommendationReason()).thenReturn(
                    CourseItemSource.USER_FIXED == sources.get(candidateId)
                            ? "사용자 선택 유지" : index == 0 ? "숲길 추천" : "둘째 추천");
            items.put(candidateId, item);
        }
        return new CoursePersistenceResult(course, items, categories);
    }

    private AccommodationDto accommodation() throws Exception {
        return objectMapper.readValue("""
                {"source_code":"KAKAO_LOCAL","source_place_id":"stay-1",
                 "place_name":"제주 숙소","address":"제주 주소",
                 "latitude":33.4,"longitude":126.8}
                """, AccommodationDto.class);
    }

    private CourseAiResultDto.DayDto day(
            LocalDate date,
            CourseAiResultDto.ItemDto... items
    ) {
        return new CourseAiResultDto.DayDto(date, List.of(items));
    }

    private CourseAiResultDto.ItemDto item(
            String candidateId,
            String startTime,
            String reason
    ) {
        return new CourseAiResultDto.ItemDto(
                candidateId, LocalTime.parse(startTime), reason);
    }
}
