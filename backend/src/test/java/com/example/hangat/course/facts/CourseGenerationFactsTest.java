package com.example.hangat.course.facts;

import com.example.hangat.map.model.enums.CongestionLevel;
import com.example.hangat.course.model.PreferenceType;
import com.example.hangat.course.model.Transport;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CourseGenerationFactsTest {

    @Test
    void createsCandidateIdentityWithIndependentIdentitySystems() {
        CandidateIdentity identity = new CandidateIdentity(
                "candidate-1", 42L, "KTO", "125266");

        assertThat(identity.candidateId()).isEqualTo("candidate-1");
        assertThat(identity.placeId()).isEqualTo(42L);
        assertThat(identity.sourcePlaceId()).isEqualTo("125266");
        assertThat(identity.candidateId()).isNotEqualTo(identity.sourcePlaceId());
    }

    @Test
    void rejectsIncompleteExternalIdentityPair() {
        assertThatThrownBy(() -> new CandidateIdentity(
                "candidate-1", null, "KTO", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("함께 존재");

        assertThatThrownBy(() -> new CandidateIdentity(
                "candidate-1", null, null, "125266"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("함께 존재");

        assertThatThrownBy(() -> new CandidateIdentity(
                "candidate-1", null, " ", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("공백");
    }

    @Test
    void rejectsBlankCandidateIdAndAvoidCandidateConstraint() {
        assertThatThrownBy(() -> new CandidateIdentity(" ", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("candidateId");

        assertThatThrownBy(() -> new UserConstraint(
                PreferenceType.AVOID, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AVOID");
    }

    @Test
    void rejectsFixedTimeWithoutFixedDate() {
        assertThatThrownBy(() -> new UserConstraint(
                PreferenceType.WANT, null, LocalTime.of(14, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fixedDate");
    }

    @Test
    void createsGeneralCandidateWithEmptyCongestionFacts() {
        CourseCandidate candidate = candidate(
                "candidate-1", UserConstraint.none(), List.of(), "weather-east-1");

        assertThat(candidate.userConstraint().preferenceType()).isNull();
        assertThat(candidate.congestionFacts()).isEmpty();
        assertThat(candidate.weatherFactSetId()).isEqualTo("weather-east-1");
    }

    @Test
    void allowsSingleCoordinateWithoutSynthesizingTheMissingCoordinate() {
        PlaceFact place = new PlaceFact(
                "좌표 일부 장소",
                null,
                null,
                new BigDecimal("33.5"),
                null,
                null);

        assertThat(place.latitude()).isEqualByComparingTo("33.5");
        assertThat(place.longitude()).isNull();
    }

    @Test
    void createsWantCandidateWithFixedSchedule() {
        LocalDate fixedDate = LocalDate.of(2026, 9, 11);
        LocalTime fixedTime = LocalTime.of(14, 0);

        CourseCandidate candidate = candidate(
                "candidate-3",
                UserConstraint.want(fixedDate, fixedTime),
                List.of(new CongestionFact(
                        null,
                        fixedDate,
                        new BigDecimal("44.50"),
                        CongestionLevel.NORMAL,
                        null)),
                null);

        assertThat(candidate.userConstraint().preferenceType()).isEqualTo(PreferenceType.WANT);
        assertThat(candidate.userConstraint().fixedDate()).isEqualTo(fixedDate);
        assertThat(candidate.userConstraint().fixedTime()).isEqualTo(fixedTime);
    }

    @Test
    void sharesWeatherFactSetByOpaqueReference() {
        WeatherFactSet weather = new WeatherFactSet(
                "weather-east-1",
                "KMA_SHORT",
                58,
                38,
                LocalDate.of(2026, 9, 10),
                LocalTime.of(5, 0),
                List.of(new WeatherFact(
                        null,
                        LocalDate.of(2026, 9, 10),
                        LocalTime.of(9, 0),
                        new BigDecimal("24.0"),
                        10,
                        "0",
                        "1",
                        new BigDecimal("2.1"),
                        60)));
        CourseCandidate first = candidate(
                "candidate-1", UserConstraint.none(), List.of(), weather.weatherFactSetId());
        CourseCandidate second = candidate(
                "candidate-2", UserConstraint.none(), List.of(), weather.weatherFactSetId());

        CourseGenerationFacts facts = new CourseGenerationFacts(
                List.of(first, second), List.of(weather), List.of());

        assertThat(facts.candidates())
                .extracting(CourseCandidate::weatherFactSetId)
                .containsExactly("weather-east-1", "weather-east-1");
        assertThat(facts.weatherFactSets()).containsExactly(weather);
    }

    @Test
    void allowsTravelFactWithoutRouteData() {
        TravelFact travel = new TravelFact(
                "candidate-1",
                "candidate-2",
                new BigDecimal("12500"),
                "HAVERSINE",
                null,
                null,
                Transport.RENTAL_CAR,
                null,
                null);

        assertThat(travel.straightDistanceMeters()).isEqualByComparingTo("12500");
        assertThat(travel.routeDistanceMeters()).isNull();
        assertThat(travel.travelMinutes()).isNull();
        assertThat(travel.routeSourceCode()).isNull();
        assertThat(travel.routeCalculatedAt()).isNull();
    }

    private CourseCandidate candidate(
            String candidateId,
            UserConstraint userConstraint,
            List<CongestionFact> congestionFacts,
            String weatherFactSetId
    ) {
        return new CourseCandidate(
                new CandidateIdentity(candidateId, null, "KTO", "source-" + candidateId),
                new PlaceFact(
                        "장소 " + candidateId,
                        "제주특별자치도 제주시",
                        null,
                        new BigDecimal("33.5000000"),
                        new BigDecimal("126.5000000"),
                        null),
                userConstraint,
                "NORTH",
                List.of(new ExternalClassificationFact(
                        "KTO", "A01", "A0101", "A01010100", null)),
                new InternalPlaceCategory(null, "TOURIST", "관광지"),
                List.of(new StyleHint("NATURE", "KTO_CAT1", "A01")),
                congestionFacts,
                weatherFactSetId);
    }
}
