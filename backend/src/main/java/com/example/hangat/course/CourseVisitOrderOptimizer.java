package com.example.hangat.course;

import com.example.hangat.course.ai.CourseAiResultDto;
import com.example.hangat.course.facts.CourseCandidate;
import com.example.hangat.course.facts.CourseGenerationFacts;
import com.example.hangat.course.model.AccommodationDto;
import com.example.hangat.course.model.CourseRequestDto;
import com.example.hangat.course.model.Transport;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Validated AI result post-processor for rental-car courses.
 *
 * <p>The selected candidates and dates are immutable inputs. Only candidates assigned to the
 * same day may exchange non-fixed time slots. Candidate-owned recommendation text moves with the
 * candidate; the chronological start-time slots stay in place.</p>
 */
public final class CourseVisitOrderOptimizer {

    private static final double EARTH_RADIUS_KM = 6371.0088;
    private static final double EPSILON_KM = 0.001;
    private static final BigDecimal JEJU_MIN_LATITUDE = new BigDecimal("32.9");
    private static final BigDecimal JEJU_MAX_LATITUDE = new BigDecimal("33.7");
    private static final BigDecimal JEJU_MIN_LONGITUDE = new BigDecimal("126.0");
    private static final BigDecimal JEJU_MAX_LONGITUDE = new BigDecimal("127.1");

    private CourseVisitOrderOptimizer() {
    }

    public static CourseAiResultDto optimize(
            CourseRequestDto request,
            CourseGenerationFacts facts,
            CourseAiResultDto result
    ) {
        if (request.getTransport() != Transport.RENTAL_CAR || result.days() == null) {
            return result;
        }

        Map<String, CourseCandidate> candidates = new HashMap<>();
        for (CourseCandidate candidate : facts.candidates()) {
            candidates.put(candidate.identity().candidateId(), candidate);
        }

        Point accommodation = point(request.getAccommodation());
        List<CourseAiResultDto.DayDto> optimizedDays = new ArrayList<>(result.days().size());
        for (CourseAiResultDto.DayDto day : result.days()) {
            optimizedDays.add(optimizeDay(day, candidates, accommodation));
        }
        return new CourseAiResultDto(result.contractVersion(), optimizedDays);
    }

    private static CourseAiResultDto.DayDto optimizeDay(
            CourseAiResultDto.DayDto day,
            Map<String, CourseCandidate> candidates,
            Point accommodation
    ) {
        if (day.items() == null || day.items().size() < 2 || day.items().size() > 7) {
            return day;
        }

        List<Visit> original = new ArrayList<>(day.items().size());
        for (int index = 0; index < day.items().size(); index++) {
            CourseAiResultDto.ItemDto item = day.items().get(index);
            CourseCandidate candidate = candidates.get(item.candidateId());
            Point point = point(candidate);
            if (candidate == null || point == null) {
                return day;
            }
            boolean fixedTime = isFixedAt(candidate, day.date(), item.startTime());
            original.add(new Visit(item, point, index, fixedTime));
        }

        Search search = new Search(original, accommodation);
        search.permute(0, new ArrayList<>(), new boolean[original.size()]);
        if (search.best == null || search.bestDistance + EPSILON_KM >= distance(original, accommodation)) {
            return day;
        }

        List<CourseAiResultDto.ItemDto> items = new ArrayList<>(original.size());
        for (int slot = 0; slot < search.best.size(); slot++) {
            Visit visit = search.best.get(slot);
            LocalTime slotTime = original.get(slot).item.startTime();
            items.add(new CourseAiResultDto.ItemDto(
                    visit.item.candidateId(), slotTime, visit.item.recommendationReason()));
        }
        return new CourseAiResultDto.DayDto(day.date(), items);
    }

    private static boolean isFixedAt(
            CourseCandidate candidate,
            LocalDate date,
            LocalTime time
    ) {
        return candidate.userConstraint().fixedDate() != null
                && candidate.userConstraint().fixedTime() != null
                && candidate.userConstraint().fixedDate().equals(date)
                && candidate.userConstraint().fixedTime().equals(time);
    }

    private static Point point(CourseCandidate candidate) {
        if (candidate == null) return null;
        return point(candidate.place().latitude(), candidate.place().longitude());
    }

    private static Point point(AccommodationDto accommodation) {
        if (accommodation == null || accommodation.getLatitude() == null
                || accommodation.getLongitude() == null) return null;
        return point(BigDecimal.valueOf(accommodation.getLatitude()),
                BigDecimal.valueOf(accommodation.getLongitude()));
    }

    private static Point point(BigDecimal latitude, BigDecimal longitude) {
        if (latitude == null || longitude == null
                || latitude.compareTo(JEJU_MIN_LATITUDE) < 0
                || latitude.compareTo(JEJU_MAX_LATITUDE) > 0
                || longitude.compareTo(JEJU_MIN_LONGITUDE) < 0
                || longitude.compareTo(JEJU_MAX_LONGITUDE) > 0) {
            return null;
        }
        return new Point(latitude.doubleValue(), longitude.doubleValue());
    }

    private static double distance(List<Visit> visits, Point anchor) {
        double total = 0;
        Point previous = anchor;
        for (Visit visit : visits) {
            if (previous != null) total += haversine(previous, visit.point);
            previous = visit.point;
        }
        if (anchor != null && previous != null) total += haversine(previous, anchor);
        return total;
    }

    private static double haversine(Point from, Point to) {
        double latitude = Math.toRadians(to.latitude - from.latitude);
        double longitude = Math.toRadians(to.longitude - from.longitude);
        double value = Math.sin(latitude / 2) * Math.sin(latitude / 2)
                + Math.cos(Math.toRadians(from.latitude)) * Math.cos(Math.toRadians(to.latitude))
                * Math.sin(longitude / 2) * Math.sin(longitude / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(value), Math.sqrt(1 - value));
    }

    private record Point(double latitude, double longitude) {
    }

    private record Visit(
            CourseAiResultDto.ItemDto item,
            Point point,
            int originalIndex,
            boolean fixedTime
    ) {
    }

    private static final class Search {
        private final List<Visit> original;
        private final Point anchor;
        private List<Visit> best;
        private double bestDistance = Double.POSITIVE_INFINITY;

        private Search(List<Visit> original, Point anchor) {
            this.original = original;
            this.anchor = anchor;
        }

        private void permute(int slot, List<Visit> current, boolean[] used) {
            if (slot == original.size()) {
                double candidateDistance = distance(current, anchor);
                if (candidateDistance + EPSILON_KM < bestDistance) {
                    bestDistance = candidateDistance;
                    best = List.copyOf(current);
                }
                return;
            }

            Visit locked = original.get(slot).fixedTime ? original.get(slot) : null;
            for (Visit candidate : original) {
                if (used[candidate.originalIndex]
                        || (locked != null && candidate != locked)
                        || (locked == null && candidate.fixedTime)) {
                    continue;
                }
                used[candidate.originalIndex] = true;
                current.add(candidate);
                permute(slot + 1, current, used);
                current.remove(current.size() - 1);
                used[candidate.originalIndex] = false;
            }
        }
    }
}
