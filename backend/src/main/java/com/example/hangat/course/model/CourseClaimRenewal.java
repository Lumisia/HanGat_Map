package com.example.hangat.course.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class CourseClaimRenewal {
    private CourseClaimRenewal() { }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Request(@NotBlank @Size(max = 4096) String claimToken) {
        @Override public String toString() { return "ClaimRenewalRequest[redacted]"; }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Response(String claimToken, Instant claimExpiresAt) {
        @Override public String toString() { return "ClaimRenewalResponse[redacted]"; }
    }
}
