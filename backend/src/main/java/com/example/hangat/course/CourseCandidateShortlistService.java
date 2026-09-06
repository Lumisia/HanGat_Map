package com.example.hangat.course;

import com.example.hangat.course.model.CourseRequestDto;
import com.example.hangat.course.model.CourseStyleDto;
import com.example.hangat.course.model.PlacePreferenceDto;
import com.example.hangat.course.model.PreferenceType;
import com.example.hangat.course.model.TourPlaceDto;
import com.example.hangat.course.model.Transport;
import org.springframework.stereotype.Service;

import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;

@Service
class CourseCandidateShortlistService {

    static final int MIN_SHORTLIST_SIZE = 15;
    static final int MAX_SHORTLIST_SIZE = 30;
    static final int CANDIDATES_PER_TRIP_DAY = 5;

    List<ShortlistedPlace> select(
            CourseRequestDto request,
            List<TourPlaceDto> rawPlaces
    ) {
        if (rawPlaces == null || rawPlaces.isEmpty()) {
            return List.of();
        }

        Set<String> selectedStyles = selectedStyleCodes(request.getCourseStyles());
        List<ShortlistedPlace> wants = new ArrayList<>();
        List<ShortlistedPlace> styleMatched = new ArrayList<>();
        List<ShortlistedPlace> remaining = new ArrayList<>();
        Set<String> seenContentIds = new LinkedHashSet<>();

        for (TourPlaceDto place : rawPlaces) {
            if (place == null || isBlank(place.getContentId())
                    || !seenContentIds.add(place.getContentId())) {
                continue;
            }

            PreferenceType preferenceType = findPreferenceType(
                    place, request.getCoursePlacePreferences());
            if (!CourseCandidateRegionFilter.shouldInclude(
                    place.getAddress(), preferenceType, request.getCourseRegions())) {
                continue;
            }

            // A required place is never removed for missing coordinates. Ordinary car
            // candidates must be usable by the existing Jeju visit-order optimizer.
            if (request.getTransport() == Transport.RENTAL_CAR
                    && preferenceType != PreferenceType.WANT && !hasCoordinates(place)) {
                continue;
            }

            List<String> hints = TourPlaceStyleHintResolver.resolve(place);
            ShortlistedPlace candidate = new ShortlistedPlace(place, preferenceType, hints);
            if (preferenceType == PreferenceType.WANT) {
                wants.add(candidate);
            } else if (matchesSelectedStyle(hints, selectedStyles)) {
                styleMatched.add(candidate);
            } else {
                remaining.add(candidate);
            }
        }

        int targetSize = targetSize(request);
        List<ShortlistedPlace> result = new ArrayList<>(wants);
        int ordinarySlots = Math.max(0, targetSize - result.size());
        Comparator<ShortlistedPlace> proximity = proximityOrder(request, wants, styleMatched, remaining);
        if (proximity != null) {
            List<ShortlistedPlace> ordinary = new ArrayList<>(styleMatched);
            ordinary.addAll(remaining);
            Set<String> names = new LinkedHashSet<>();
            wants.forEach(c -> names.add(displayVarietyKey(c.place().getTitle())));
            if (request.getCoursePlacePreferences() != null) {
                request.getCoursePlacePreferences().stream()
                        .filter(p -> p != null && p.getPreferenceType() == PreferenceType.WANT)
                        .forEach(p -> names.add(displayVarietyKey(p.getPlaceName())));
            }
            ordinary.stream().sorted(proximity).filter(c -> {
                String key = displayVarietyKey(c.place().getTitle());
                return !key.isEmpty() && names.add(key);
            }).limit(ordinarySlots).forEach(result::add);
            return List.copyOf(result);
        }
        appendUpTo(result, proximity == null ? diversify(styleMatched)
                : styleMatched.stream().sorted(proximity).toList(), ordinarySlots);
        ordinarySlots = Math.max(0, targetSize - result.size());
        appendUpTo(result, proximity == null ? diversify(remaining)
                : remaining.stream().sorted(proximity).toList(), ordinarySlots);
        return List.copyOf(result);
    }

    // No new distance threshold or day-to-region policy. Restrict this ranking to
    // empty or one selected region; explicit multi-region allocation is unchanged.
    private Comparator<ShortlistedPlace> proximityOrder(CourseRequestDto request,
            List<ShortlistedPlace> wants, List<ShortlistedPlace> matched,
            List<ShortlistedPlace> remaining) {
        if (request.getTransport() != Transport.RENTAL_CAR
                || request.getCourseRegions().size() > 1) return null;
        List<double[]> anchors = new ArrayList<>();
        if (request.getCoursePlacePreferences() != null) {
            request.getCoursePlacePreferences().stream()
                    .filter(p -> p != null && p.getPreferenceType() == PreferenceType.WANT
                            && validCoordinates(p.getLatitude(), p.getLongitude()))
                    .forEach(p -> anchors.add(new double[]{p.getLatitude(), p.getLongitude()}));
        }
        if (anchors.isEmpty()) {
            wants.stream().map(ShortlistedPlace::place).filter(this::hasCoordinates)
                    .forEach(p -> anchors.add(new double[]{p.getLatitude(), p.getLongitude()}));
        }
        List<ShortlistedPlace> ordinary = new ArrayList<>(matched);
        ordinary.addAll(remaining);
        ordinary.sort(Comparator.comparing(c -> c.place().getContentId()));
        if (anchors.isEmpty() && !ordinary.isEmpty()) {
            TourPlaceDto center = ordinary.stream().min(Comparator
                    .comparingDouble((ShortlistedPlace c) -> ordinary.stream()
                            .mapToDouble(o -> distance(c.place(), o.place().getLatitude(), o.place().getLongitude())).sum())
                    .thenComparing(c -> c.place().getContentId())).orElseThrow().place();
            anchors.add(new double[]{center.getLatitude(), center.getLongitude()});
        }
        return Comparator.comparingDouble((ShortlistedPlace c) -> anchors.stream()
                .mapToDouble(a -> distance(c.place(), a[0], a[1])).min().orElse(0))
                .thenComparing(c -> c.place().getContentId());
    }

    private boolean hasCoordinates(TourPlaceDto p) {
        return validCoordinates(p.getLatitude(), p.getLongitude());
    }

    // Recommendation variety only: never used for provider identity or DB merging.
    private String displayVarietyKey(String title) {
        if (title == null) return "";
        return normalizePlaceName(title.replaceAll("\\([^()]*\\)", ""));
    }

    private boolean validCoordinates(Double lat, Double lon) {
        // Same existing Jeju envelope as CourseVisitOrderOptimizer; not a region polygon.
        return lat != null && lon != null && Double.isFinite(lat) && Double.isFinite(lon)
                && lat >= 32.9 && lat <= 33.7 && lon >= 126.0 && lon <= 127.1;
    }

    private double distance(TourPlaceDto p, double lat, double lon) {
        double a = Math.pow(Math.sin(Math.toRadians(p.getLatitude() - lat) / 2), 2)
                + Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(p.getLatitude()))
                * Math.pow(Math.sin(Math.toRadians(p.getLongitude() - lon) / 2), 2);
        return 2 * Math.asin(Math.sqrt(Math.min(1, a)));
    }

    private int targetSize(CourseRequestDto request) {
        long tripDays = ChronoUnit.DAYS.between(
                request.getStartDate(), request.getEndDate()) + 1;
        long requested = tripDays * CANDIDATES_PER_TRIP_DAY;
        return (int) Math.min(
                MAX_SHORTLIST_SIZE,
                Math.max(MIN_SHORTLIST_SIZE, requested));
    }

    private void appendUpTo(
            List<ShortlistedPlace> target,
            List<ShortlistedPlace> source,
            int limit
    ) {
        target.addAll(source.subList(0, Math.min(limit, source.size())));
    }

    private List<ShortlistedPlace> diversify(List<ShortlistedPlace> candidates) {
        Map<String, Deque<ShortlistedPlace>> buckets = new LinkedHashMap<>();
        for (ShortlistedPlace candidate : candidates) {
            buckets.computeIfAbsent(diversityKey(candidate.place()), ignored -> new ArrayDeque<>())
                    .addLast(candidate);
        }

        List<ShortlistedPlace> diversified = new ArrayList<>(candidates.size());
        boolean added;
        do {
            added = false;
            for (Deque<ShortlistedPlace> bucket : buckets.values()) {
                ShortlistedPlace next = bucket.pollFirst();
                if (next != null) {
                    diversified.add(next);
                    added = true;
                }
            }
        } while (added);
        return diversified;
    }

    private String diversityKey(TourPlaceDto place) {
        String region = TourPlaceRegionResolver.resolve(place.getAddress())
                .map(Enum::name)
                .orElse("UNKNOWN");
        String category = isBlank(place.getCategory()) ? "UNCATEGORIZED" : place.getCategory();
        return region + '|' + category;
    }

    private boolean matchesSelectedStyle(List<String> hints, Set<String> selectedStyles) {
        if (hints.isEmpty() || selectedStyles.isEmpty()) {
            return false;
        }
        return hints.stream().anyMatch(selectedStyles::contains);
    }

    private Set<String> selectedStyleCodes(List<CourseStyleDto> styles) {
        if (styles == null || styles.isEmpty()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (CourseStyleDto style : styles) {
            if (style != null && !isBlank(style.getCode())) {
                result.add(style.getCode().trim().toUpperCase(Locale.ROOT));
            }
        }
        return Set.copyOf(result);
    }

    private PreferenceType findPreferenceType(
            TourPlaceDto place,
            List<PlacePreferenceDto> preferences
    ) {
        if (preferences == null || preferences.isEmpty()) {
            return null;
        }

        PreferenceType matched = null;
        for (PlacePreferenceDto preference : preferences) {
            if (preference == null || preference.getPreferenceType() == null
                    || !matchesTourPlace(place, preference)) {
                continue;
            }
            if (preference.getPreferenceType() == PreferenceType.AVOID) {
                return PreferenceType.AVOID;
            }
            matched = PreferenceType.WANT;
        }
        return matched;
    }

    private boolean matchesTourPlace(TourPlaceDto place, PlacePreferenceDto preference) {
        String placeTitle = normalizePlaceName(place.getTitle());
        String preferenceName = normalizePlaceName(preference.getPlaceName());
        return !placeTitle.isEmpty() && placeTitle.equals(preferenceName);
    }

    private String normalizePlaceName(String value) {
        return value == null
                ? ""
                : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    record ShortlistedPlace(
            TourPlaceDto place,
            PreferenceType preferenceType,
            List<String> confirmedStyleHints
    ) {
        ShortlistedPlace {
            confirmedStyleHints = List.copyOf(confirmedStyleHints);
        }
    }
}
