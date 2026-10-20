package com.example.hangat.course.travel;

import com.example.hangat.course.model.TourPlaceDto;
import com.example.hangat.course.model.Transport;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.OptionalDouble;

@Service
public class CourseTravelService {

    private static final int DISTANCE_SCALE = 3;

    private final StraightLineDistanceCalculator distanceCalculator;

    public CourseTravelService(StraightLineDistanceCalculator distanceCalculator) {
        this.distanceCalculator = distanceCalculator;
    }

    public Optional<CourseTravelLegDto> calculateStraightLineLeg(
            TourPlaceDto from,
            TourPlaceDto to,
            Transport transport
    ) {
        if (from == null || to == null || transport == null) {
            return Optional.empty();
        }

        OptionalDouble distance = distanceCalculator.calculateKilometers(
                from.getLatitude(),
                from.getLongitude(),
                to.getLatitude(),
                to.getLongitude());
        if (distance.isEmpty()) {
            return Optional.empty();
        }

        BigDecimal straightKm = BigDecimal.valueOf(distance.getAsDouble())
                .setScale(DISTANCE_SCALE, RoundingMode.HALF_UP);
        return Optional.of(new CourseTravelLegDto(
                from.getContentId(),
                from.getTitle(),
                to.getContentId(),
                to.getTitle(),
                straightKm,
                DistanceCalculationMethod.HAVERSINE,
                null,
                estimatedMinutes(straightKm, transport),
                transport,
                null,
                null));
    }

    /** Planning estimate only. It is never represented as a provider route result. */
    private int estimatedMinutes(BigDecimal straightKm, Transport transport) {
        double kilometresPerHour = switch (transport) {
            case RENTAL_CAR -> 35d;
            case TAXI -> 32d;
            case PUBLIC_TRANSIT -> 18d;
            case WALK_BIKE -> 5d;
        };
        return Math.max(1, (int) Math.ceil(straightKm.doubleValue() / kilometresPerHour * 60d));
    }

    public boolean isWithinAlternativeRadius(
            TourPlaceDto origin,
            TourPlaceDto candidate,
            double radiusKm
    ) {
        if (origin == null || candidate == null) {
            return false;
        }
        return distanceCalculator.isWithinKilometers(
                origin.getLatitude(),
                origin.getLongitude(),
                candidate.getLatitude(),
                candidate.getLongitude(),
                radiusKm);
    }
}
