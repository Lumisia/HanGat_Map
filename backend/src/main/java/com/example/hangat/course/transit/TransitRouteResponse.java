package com.example.hangat.course.transit;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.*;
import java.util.List;
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TransitRouteResponse(Long courseId, Instant queriedAt, boolean cached, int providerAttempts, List<Day> days) {
    public TransitRouteResponse cacheHit() { return new TransitRouteResponse(courseId, queriedAt, true, 0, days); }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Day(int dayNo, LocalDate visitDate, Long distanceMeters, Long durationSeconds, List<Leg> legs) {}
    public record Stop(String id, String name, Double latitude, Double longitude) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Leg(Stop from, Stop to, String status, Long distanceMeters, Long durationSeconds,
                      Integer transfers, String landingUrl, List<Step> steps) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Step(String type, Long distanceMeters, Long durationSeconds, List<String> stops, List<String> vehicles) {}
}
