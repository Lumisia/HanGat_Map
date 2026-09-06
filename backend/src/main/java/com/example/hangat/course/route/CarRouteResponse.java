package com.example.hangat.course.route;

import com.example.hangat.course.model.enums.Transport;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CarRouteResponse(Long courseId, Transport transport, String provider,
        String priority, boolean cached, OffsetDateTime fetchedAt, List<DayRoute> days) {
    public CarRouteResponse withCached(boolean value) {
        return new CarRouteResponse(courseId, transport, provider, priority, value, fetchedAt, days);
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DayRoute(int dayNo, LocalDate visitDate, Integer totalDistanceMeters,
            Integer totalDurationSeconds, List<Leg> legs, List<Coordinate> polyline) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Leg(Stop from, Stop to, Integer distanceMeters, Integer durationSeconds,
            List<Coordinate> polyline) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Stop(String type, String id, String name, AccessPoint accessPoint) {
        public Stop(String type, String id, String name) { this(type, id, name, null); }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AccessPoint(Long originalPlaceId, String originalPlaceName, String type,
            String name, double latitude, double longitude, long straightDistanceMeters,
            String sourceCode, String sourcePlaceId, String notice) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Coordinate(double latitude, double longitude) {}
}
