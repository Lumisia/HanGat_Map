package com.example.hangat.course.travel;

import com.example.hangat.course.model.TourPlaceDto;
import com.example.hangat.course.model.Transport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CourseTravelServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CourseTravelService service = new CourseTravelService(
            new StraightLineDistanceCalculator());

    @ParameterizedTest
    @EnumSource(Transport.class)
    void straightDistanceProvidesClearlyNonProviderPlanningDurationForAnyTransport(Transport transport) throws Exception {
        TourPlaceDto from = place("100", "성산일출봉", 33.4581, 126.9425);
        TourPlaceDto to = place("200", "비자림", 33.4913, 126.8114);

        CourseTravelLegDto leg = service.calculateStraightLineLeg(from, to, transport).orElseThrow();

        assertThat(leg.fromCandidateId()).isEqualTo("100");
        assertThat(leg.toCandidateId()).isEqualTo("200");
        assertThat(leg.straightDistanceKm()).isPositive();
        assertThat(leg.straightDistanceMethod()).isEqualTo(DistanceCalculationMethod.HAVERSINE);
        assertThat(leg.transport()).isEqualTo(transport);
        assertThat(leg.routeDistanceKm()).isNull();
        assertThat(leg.durationMinutes()).isPositive();
        assertThat(leg.routeSourceCode()).isNull();
        assertThat(leg.routeCalculatedAt()).isNull();
        assertThat(leg.hasRouteData()).isFalse();
    }

    @Test
    void unavailableCoordinateReturnsNoFactInsteadOfZeroDistance() throws Exception {
        TourPlaceDto from = place("100", "좌표 없음", null, 126.9425);
        TourPlaceDto to = place("200", "비자림", 33.4913, 126.8114);

        assertThat(service.calculateStraightLineLeg(from, to, Transport.RENTAL_CAR)).isEmpty();
    }

    @Test
    void missingPlaceOrTransportReturnsEmpty() throws Exception {
        TourPlaceDto place = place("100", "성산일출봉", 33.4581, 126.9425);

        assertThat(service.calculateStraightLineLeg(null, place, Transport.RENTAL_CAR)).isEmpty();
        assertThat(service.calculateStraightLineLeg(place, null, Transport.RENTAL_CAR)).isEmpty();
        assertThat(service.calculateStraightLineLeg(place, place, null)).isEmpty();
    }

    @Test
    void alternativeRadiusUsesStraightDistanceAndRejectsUnavailableCoordinate() throws Exception {
        TourPlaceDto origin = place("100", "기준", 0.0, 0.0);
        double tenKilometerDelta = Math.toDegrees(
                10.0 / StraightLineDistanceCalculator.EARTH_MEAN_RADIUS_KM);
        TourPlaceDto boundary = place("200", "10km 경계", tenKilometerDelta, 0.0);
        TourPlaceDto outside = place("300", "10km 밖", Math.toDegrees(
                10.01 / StraightLineDistanceCalculator.EARTH_MEAN_RADIUS_KM), 0.0);
        TourPlaceDto unavailable = place("400", "좌표 없음", null, 0.0);

        assertThat(service.isWithinAlternativeRadius(origin, boundary, 10.0)).isTrue();
        assertThat(service.isWithinAlternativeRadius(origin, outside, 10.0)).isFalse();
        assertThat(service.isWithinAlternativeRadius(origin, unavailable, 20.0)).isFalse();
    }

    private TourPlaceDto place(
            String contentId,
            String title,
            Double latitude,
            Double longitude
    ) throws Exception {
        String latitudeJson = latitude == null ? "null" : latitude.toString();
        String longitudeJson = longitude == null ? "null" : longitude.toString();
        return objectMapper.readValue("""
                {
                  "contentid": "%s",
                  "title": "%s",
                  "mapy": %s,
                  "mapx": %s
                }
                """.formatted(contentId, title, latitudeJson, longitudeJson), TourPlaceDto.class);
    }
}
