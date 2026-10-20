package com.example.hangat.course;

import com.example.hangat.course.facts.CourseGenerationFacts;
import com.example.hangat.course.facts.TravelFact;
import com.example.hangat.course.facts.WeatherFact;
import com.example.hangat.course.facts.WeatherFactSet;
import com.example.hangat.course.model.CongestionDto;
import com.example.hangat.map.model.enums.CongestionLevel;
import com.example.hangat.course.model.CourseCandidateDto;
import com.example.hangat.course.model.CourseRequestDto;
import com.example.hangat.course.model.TourPlaceDto;
import com.example.hangat.course.model.Transport;
import com.example.hangat.course.travel.CourseTravelLegDto;
import com.example.hangat.course.travel.DistanceCalculationMethod;
import com.example.hangat.course.weather.CourseWeatherFacts;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class CourseGenerationFactsAssemblerTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());
    private final CourseGenerationFactsAssembler assembler =
            new CourseGenerationFactsAssembler();

    @ParameterizedTest
    @CsvSource({
            "0, QUIET",
            "33.3, QUIET",
            "39.9, QUIET",
            "42.5, NORMAL",
            "69.9, NORMAL",
            "70, CROWDED",
            "82.0, CROWDED",
            "100, CROWDED"
    })
    void enrichesOnlyValidCongestionRates(String rate, CongestionLevel level) throws Exception {
        CourseGenerationFacts facts = assemble(
                List.of(candidate("candidate-1", List.of(congestion("20260910", rate)))),
                CourseWeatherFacts.empty(),
                List.of());

        assertThat(facts.candidates().get(0).congestionFacts()).singleElement().satisfies(fact -> {
            assertThat(fact.date()).isEqualTo(LocalDate.of(2026, 9, 10));
            assertThat(fact.rate()).isEqualByComparingTo(rate);
            assertThat(fact.level()).isEqualTo(level);
        });
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "not-a-number", "-0.01", "100.01"})
    void doesNotCreateCongestionFactForMissingOrInvalidRate(String rate) throws Exception {
        CourseGenerationFacts facts = assemble(
                List.of(candidate("candidate-1", List.of(congestion("20260910", rate)))),
                CourseWeatherFacts.empty(),
                List.of());

        assertThat(facts.candidates().get(0).congestionFacts()).isEmpty();
    }

    @Test
    void keepsCongestionFactsEmptyWhenCandidateHasNoMatchedData() throws Exception {
        CourseGenerationFacts facts = assemble(
                List.of(candidate("candidate-1", List.of())),
                CourseWeatherFacts.empty(),
                List.of());

        assertThat(facts.candidates().get(0).congestionFacts()).isEmpty();
    }

    @Test
    void doesNotCreateCongestionFactForInvalidDate() throws Exception {
        CourseGenerationFacts facts = assemble(
                List.of(candidate("candidate-1", List.of(congestion("invalid", "20")))),
                CourseWeatherFacts.empty(),
                List.of());

        assertThat(facts.candidates().get(0).congestionFacts()).isEmpty();
    }

    @Test
    void sharesVerifiedWeatherFactSetAndPreservesAllMappedCategories() throws Exception {
        WeatherFact weather = weatherFact();
        WeatherFactSet weatherSet = weatherFactSet("east-weather", List.of(weather));
        CourseWeatherFacts verifiedWeather = new CourseWeatherFacts(
                Map.of("candidate-1", "east-weather", "candidate-2", "east-weather"),
                List.of(weatherSet));

        CourseGenerationFacts facts = assemble(
                List.of(candidate("candidate-1", List.of()), candidate("candidate-2", List.of())),
                verifiedWeather,
                List.of());

        assertThat(facts.weatherFactSets()).containsExactly(weatherSet);
        assertThat(facts.candidates()).extracting("weatherFactSetId")
                .containsExactly("east-weather", "east-weather");
        assertThat(weather.temperature()).isEqualByComparingTo("27.5");
        assertThat(weather.precipitationProbability()).isEqualTo(30);
        assertThat(weather.precipitationTypeCode()).isEqualTo("0");
        assertThat(weather.skyConditionCode()).isEqualTo("3");
        assertThat(weather.windSpeed()).isEqualByComparingTo("2.5");
        assertThat(weather.humidity()).isEqualTo(65);
    }

    @Test
    void doesNotCreateFakeWeatherWhenVerifiedWeatherIsMissing() throws Exception {
        CourseGenerationFacts facts = assemble(
                List.of(candidate("candidate-1", List.of())),
                CourseWeatherFacts.empty(),
                List.of());

        assertThat(facts.weatherFactSets()).isEmpty();
        assertThat(facts.candidates().get(0).weatherFactSetId()).isNull();
    }

    @Test
    void rejectsWeatherReferenceToUnknownCandidate() throws Exception {
        CourseWeatherFacts weather = new CourseWeatherFacts(
                Map.of("missing", "east-weather"),
                List.of(weatherFactSet("east-weather", List.of(weatherFact()))));

        assertThatIllegalArgumentException().isThrownBy(() -> assemble(
                List.of(candidate("candidate-1", List.of())), weather, List.of()));
    }

    @Test
    void rejectsWeatherReferenceToUnknownFactSet() throws Exception {
        CourseWeatherFacts weather = new CourseWeatherFacts(
                Map.of("candidate-1", "missing-weather"), List.of());

        assertThatIllegalArgumentException().isThrownBy(() -> assemble(
                List.of(candidate("candidate-1", List.of())), weather, List.of()));
    }

    @Test
    void rejectsDuplicateWeatherFactSetId() throws Exception {
        WeatherFactSet first = weatherFactSet("east-weather", List.of(weatherFact()));
        WeatherFactSet second = weatherFactSet("east-weather", List.of());

        assertThatIllegalArgumentException().isThrownBy(() -> assemble(
                List.of(candidate("candidate-1", List.of())),
                new CourseWeatherFacts(Map.of(), List.of(first, second)),
                List.of()));
    }

    @Test
    void convertsHaversineKilometersToMetersWithoutInventingRouteData() throws Exception {
        CourseTravelLegDto leg = travelLeg("candidate-1", "candidate-2", "8.200");

        CourseGenerationFacts facts = assemble(
                List.of(candidate("candidate-1", List.of()), candidate("candidate-2", List.of())),
                CourseWeatherFacts.empty(),
                List.of(leg));

        assertThat(facts.travelFacts()).singleElement().satisfies(fact -> {
            assertThat(fact.fromCandidateId()).isEqualTo("candidate-1");
            assertThat(fact.toCandidateId()).isEqualTo("candidate-2");
            assertThat(fact.straightDistanceMeters()).isEqualByComparingTo("8200");
            assertThat(fact.straightDistanceMethod()).isEqualTo("HAVERSINE");
            assertThat(fact.routeDistanceMeters()).isNull();
            assertThat(fact.travelMinutes()).isNull();
        });
    }

    @Test
    void rejectsTravelFactThatReferencesUnknownCandidate() throws Exception {
        assertThatIllegalArgumentException().isThrownBy(() -> assemble(
                List.of(candidate("candidate-1", List.of())),
                CourseWeatherFacts.empty(),
                List.of(travelLeg("candidate-1", "missing", "1.0"))));
    }

    @Test
    void rejectsDuplicateTravelPair() throws Exception {
        CourseTravelLegDto leg = travelLeg("candidate-1", "candidate-2", "1.0");

        assertThatIllegalArgumentException().isThrownBy(() -> assemble(
                List.of(candidate("candidate-1", List.of()), candidate("candidate-2", List.of())),
                CourseWeatherFacts.empty(),
                List.of(leg, leg)));
    }

    @Test
    void preservesTransportWithoutEstimatingTravelMinutes() throws Exception {
        CourseGenerationFacts facts = assemble(
                List.of(candidate("candidate-1", List.of()), candidate("candidate-2", List.of())),
                CourseWeatherFacts.empty(),
                List.of(travelLeg("candidate-1", "candidate-2", "1.0")));

        TravelFact fact = facts.travelFacts().get(0);
        assertThat(fact.transport()).isEqualTo(Transport.RENTAL_CAR);
        assertThat(fact.travelMinutes()).isNull();
    }

    @Test
    void assemblesCandidatesWeatherAndTravelIntoOneImmutableSnapshot() throws Exception {
        WeatherFactSet weatherSet = weatherFactSet("east-weather", List.of(weatherFact()));
        CourseGenerationFacts facts = assemble(
                List.of(candidate("candidate-1", List.of()), candidate("candidate-2", List.of())),
                new CourseWeatherFacts(
                        Map.of("candidate-1", "east-weather"), List.of(weatherSet)),
                List.of(travelLeg("candidate-1", "candidate-2", "2.345")));

        assertThat(facts.candidates()).hasSize(2);
        assertThat(facts.weatherFactSets()).containsExactly(weatherSet);
        assertThat(facts.travelFacts()).hasSize(1);
        assertThat(facts.candidates()).extracting(candidate -> candidate.identity().candidateId())
                .containsExactly("candidate-1", "candidate-2").doesNotHaveDuplicates();
    }

    @Test
    void preservesCandidateInvariantAfterEnrichment() throws Exception {
        CourseGenerationFacts facts = assemble(
                List.of(candidate("candidate-1", List.of(congestion("20260910", "20")))),
                CourseWeatherFacts.empty(),
                List.of());

        assertThat(facts.candidates().get(0).identity().sourceCode()).isEqualTo("KTO");
        assertThat(facts.candidates().get(0).identity().sourcePlaceId())
                .isEqualTo("candidate-1");
        assertThat(facts.candidates().get(0).place().name()).isEqualTo("장소 candidate-1");
    }

    @Test
    void permitsEmptyWeatherAndTravelCollections() throws Exception {
        CourseGenerationFacts facts = assemble(
                List.of(candidate("candidate-1", List.of())), null, null);

        assertThat(facts.candidates()).hasSize(1);
        assertThat(facts.weatherFactSets()).isEmpty();
        assertThat(facts.travelFacts()).isEmpty();
    }

    private CourseGenerationFacts assemble(
            List<CourseCandidateDto> candidates,
            CourseWeatherFacts weather,
            List<CourseTravelLegDto> travel
    ) throws Exception {
        return assembler.assemble(request(), candidates, weather, travel).facts();
    }

    private CourseRequestDto request() throws Exception {
        return objectMapper.readValue("""
                {
                  "start_date": "2026-09-10",
                  "end_date": "2026-09-12",
                  "transport": "RENTAL_CAR",
                  "course_place_preferences": []
                }
                """, CourseRequestDto.class);
    }

    private CourseCandidateDto candidate(
            String id,
            List<CongestionDto> congestion
    ) throws Exception {
        TourPlaceDto place = objectMapper.readValue("""
                {
                  "contentid": "%s",
                  "title": "장소 %s",
                  "addr1": "제주특별자치도 제주시 구좌읍",
                  "mapy": 33.48,
                  "mapx": 126.81,
                  "cat1": "A01"
                }
                """.formatted(id, id), TourPlaceDto.class);
        return new CourseCandidateDto(place, congestion, null, List.of("NATURE"));
    }

    private CongestionDto congestion(String date, String rate) throws Exception {
        String rateJson = rate == null ? "null" : "\"" + rate + "\"";
        return objectMapper.readValue(
                "{\"baseYmd\":\"%s\",\"cnctrRate\":%s}"
                        .formatted(date, rateJson),
                CongestionDto.class);
    }

    private WeatherFact weatherFact() {
        return new WeatherFact(
                null,
                LocalDate.of(2026, 9, 10),
                LocalTime.of(14, 0),
                new BigDecimal("27.5"),
                30,
                "0",
                "3",
                new BigDecimal("2.5"),
                65);
    }

    private WeatherFactSet weatherFactSet(String id, List<WeatherFact> facts) {
        return new WeatherFactSet(
                id,
                "KMA",
                55,
                38,
                LocalDate.of(2026, 9, 9),
                LocalTime.of(5, 0),
                facts);
    }

    private CourseTravelLegDto travelLeg(String from, String to, String distanceKm) {
        return new CourseTravelLegDto(
                from,
                "출발",
                to,
                "도착",
                new BigDecimal(distanceKm),
                DistanceCalculationMethod.HAVERSINE,
                null,
                null,
                Transport.RENTAL_CAR,
                null,
                null);
    }
}
