package com.example.hangat.course.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CourseAccommodationUpdateRequest(
        @NotNull @Valid AccommodationDto accommodation,
        String claimToken
) {
}
