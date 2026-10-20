package com.example.hangat.course;

import com.example.hangat.course.facts.CandidateIdentity;
import com.example.hangat.course.facts.CongestionFact;
import com.example.hangat.course.facts.CourseCandidate;
import com.example.hangat.course.facts.ExternalClassificationFact;
import com.example.hangat.course.facts.InternalPlaceCategory;
import com.example.hangat.course.facts.PlaceFact;
import com.example.hangat.course.facts.StyleHint;
import com.example.hangat.course.facts.UserConstraint;
import com.example.hangat.course.model.CongestionDto;
import com.example.hangat.course.model.CourseCandidateDto;
import com.example.hangat.course.model.CourseRequestDto;
import com.example.hangat.course.model.PlacePreferenceDto;
import com.example.hangat.course.model.PreferenceType;
import com.example.hangat.course.model.TourPlaceDto;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Normalizes provider-specific course candidates into the provider-neutral facts model.
 *
 * <p>The existing candidate ids are deliberately preserved as a compatibility bridge until
 * persistence and response projection consume {@link CourseCandidate} directly.</p>
 */
final class CourseCandidateNormalizer {

    static final String KTO_SOURCE_CODE = "KTO";
    static final String KAKAO_SOURCE_CODE = "KAKAO_LOCAL";
    private static final double SAME_PLACE_COORDINATE_EPSILON = 0.0002;
    private static final DateTimeFormatter CONGESTION_DATE_FORMATTER =
            DateTimeFormatter.BASIC_ISO_DATE;

    NormalizationResult normalize(
            CourseRequestDto request,
            List<CourseCandidateDto> providerCandidates
    ) {
        List<CourseCandidateDto> safeCandidates = providerCandidates == null
                ? List.of()
                : providerCandidates;
        List<CourseCandidate> normalized = new ArrayList<>();
        Set<String> candidateIds = new HashSet<>();
        Map<PlacePreferenceDto, String> candidateIdsByPreference = new IdentityHashMap<>();

        for (CourseCandidateDto candidate : safeCandidates) {
            if (candidate == null || candidate.getPlace() == null
                    || candidate.getPreferenceType() == PreferenceType.AVOID) {
                continue;
            }
            CourseCandidate normalizedCandidate = candidate.getStoredCandidate() == null
                    ? normalizeKtoCandidate(request, candidate) : candidate.getStoredCandidate();
            String candidateId = normalizedCandidate.identity().candidateId();
            if (!candidateIds.add(candidateId)) {
                throw new IllegalArgumentException("중복된 AI 후보 식별자입니다: " + candidateId);
            }
            normalized.add(normalizedCandidate);
        }

        if (request != null && request.getCoursePlacePreferences() != null) {
            int requestWantOrdinal = 1;
            for (PlacePreferenceDto preference : request.getCoursePlacePreferences()) {
                if (preference == null || preference.getPreferenceType() != PreferenceType.WANT) {
                    continue;
                }

                int matchedIndex = findMatchingCandidateIndex(preference, normalized);
                if (matchedIndex >= 0) {
                    CourseCandidate matched = normalized.get(matchedIndex);
                    if (isKakaoPreference(preference) && matched.identity().placeId() == null) {
                        normalized.set(matchedIndex, normalizeKakaoWant(
                                matched.identity().candidateId(), preference));
                    } else if (matched.userConstraint().preferenceType() != PreferenceType.WANT) {
                        normalized.set(matchedIndex, withUserConstraint(
                                matched, UserConstraint.want(
                                        preference.getFixedDate(), preference.getFixedTime())));
                    }
                    candidateIdsByPreference.put(
                            preference, matched.identity().candidateId());
                    continue;
                }

                if (!isKakaoPreference(preference)) {
                    continue;
                }
                String candidateId;
                do {
                    candidateId = "request-want-" + requestWantOrdinal++;
                } while (!candidateIds.add(candidateId));
                normalized.add(normalizeKakaoWant(candidateId, preference));
                candidateIdsByPreference.put(preference, candidateId);
            }
        }

        return new NormalizationResult(
                List.copyOf(normalized), Map.copyOf(candidateIdsByPreference));
    }

    private CourseCandidate normalizeKtoCandidate(
            CourseRequestDto request,
            CourseCandidateDto candidate
    ) {
        TourPlaceDto place = candidate.getPlace();
        String candidateId = requireCandidateId(place.getContentId());
        PlacePreferenceDto matchedWant = findMatchingWant(request, place);
        UserConstraint constraint = matchedWant == null
                ? (candidate.getPreferenceType() == PreferenceType.WANT
                        ? UserConstraint.want(null, null)
                        : UserConstraint.none())
                : UserConstraint.want(matchedWant.getFixedDate(), matchedWant.getFixedTime());

        return new CourseCandidate(
                new CandidateIdentity(candidateId, null, KTO_SOURCE_CODE, place.getContentId()),
                new PlaceFact(
                        place.getTitle(), place.getAddress(), null,
                        decimal(place.getLatitude()), decimal(place.getLongitude()),
                        blankToNull(place.getImageUrl())),
                constraint,
                TourPlaceRegionResolver.resolve(place.getAddress())
                        .map(Enum::name)
                        .orElse(CourseAiInputAssembler.UNKNOWN_REGION),
                ktoClassifications(place),
                ktoInternalCategory(place),
                ktoStyleHints(place, candidate.getConfirmedStyleHints()),
                congestionFacts(candidate.getCongestionData()),
                null
        );
    }

    private CourseCandidate normalizeKakaoWant(
            String candidateId,
            PlacePreferenceDto preference
    ) {
        String addressForRegion = firstNonBlank(
                preference.getRoadAddress(), preference.getAddress());
        return new CourseCandidate(
                new CandidateIdentity(
                        candidateId,
                        preference.getPlaceId(),
                        KAKAO_SOURCE_CODE,
                        preference.getSourcePlaceId().trim()),
                new PlaceFact(
                        preference.getPlaceName(),
                        preference.getAddress(),
                        preference.getRoadAddress(),
                        decimal(preference.getLatitude()),
                        decimal(preference.getLongitude()),
                        null),
                UserConstraint.want(preference.getFixedDate(), preference.getFixedTime()),
                TourPlaceRegionResolver.resolve(addressForRegion)
                        .map(Enum::name)
                        .orElse(CourseAiInputAssembler.UNKNOWN_REGION),
                kakaoClassifications(preference),
                kakaoInternalCategory(preference.getCategoryName()),
                List.of(),
                List.of(),
                null
        );
    }

    private List<ExternalClassificationFact> ktoClassifications(TourPlaceDto place) {
        if (isBlank(place.getCategory()) && isBlank(place.getCategory2())
                && isBlank(place.getCategory3())) {
            return List.of();
        }
        return List.of(new ExternalClassificationFact(
                KTO_SOURCE_CODE,
                blankToNull(place.getCategory()),
                blankToNull(place.getCategory2()),
                blankToNull(place.getCategory3()),
                null));
    }

    private List<ExternalClassificationFact> kakaoClassifications(
            PlacePreferenceDto preference
    ) {
        if (isBlank(preference.getCategoryName())) {
            return List.of();
        }
        return List.of(new ExternalClassificationFact(
                KAKAO_SOURCE_CODE, null, null, null, preference.getCategoryName()));
    }

    private InternalPlaceCategory ktoInternalCategory(TourPlaceDto place) {
        String code;
        if ("A05020900".equals(place.getCategory3())
                || StoredPlaceStyleResolver.resolve(place.getClassificationCode()).contains("CAFE")) {
            code = "CAFE";
        } else if ("A05".equals(place.getCategory())) {
            code = "FOOD";
        } else if ("B02".equals(place.getCategory())) {
            code = "LODGING";
        } else if ("A01".equals(place.getCategory())
                || "A02".equals(place.getCategory())
                || "A03".equals(place.getCategory())) {
            code = "TOURIST";
        } else {
            return null;
        }
        return internalCategory(code);
    }

    private InternalPlaceCategory kakaoInternalCategory(String categoryName) {
        return KakaoPlaceCategoryResolver.resolve(categoryName)
                .map(this::internalCategory)
                .orElse(null);
    }

    private InternalPlaceCategory internalCategory(String code) {
        String name = switch (code) {
            case "TOURIST" -> "관광지";
            case "CAFE" -> "카페";
            case "FOOD" -> "음식점";
            case "LODGING" -> "숙박";
            default -> throw new IllegalArgumentException("지원하지 않는 내부 장소 카테고리입니다.");
        };
        return new InternalPlaceCategory(null, code, name);
    }

    private List<StyleHint> ktoStyleHints(
            TourPlaceDto place,
            List<String> confirmedStyleHints
    ) {
        List<StyleHint> result = new ArrayList<>();
        if (confirmedStyleHints == null) {
            return List.of();
        }
        for (String styleCode : confirmedStyleHints) {
            if (isBlank(styleCode)) {
                continue;
            }
            String normalizedCode = styleCode.trim().toUpperCase(Locale.ROOT);
            if (StoredPlaceStyleResolver.resolve(place.getClassificationCode()).contains(normalizedCode)) {
                result.add(new StyleHint(normalizedCode, "KTO_CLASSIFICATION", place.getClassificationCode()));
            } else if ("NATURE".equals(normalizedCode) && "A01".equals(place.getCategory())) {
                result.add(new StyleHint("NATURE", "KTO_CAT1", "A01"));
            } else if ("ACTIVITY".equals(normalizedCode) && "A03".equals(place.getCategory())) {
                result.add(new StyleHint("ACTIVITY", "KTO_CAT1", "A03"));
            } else if ("CAFE".equals(normalizedCode)
                    && "A05020900".equals(place.getCategory3())) {
                result.add(new StyleHint("CAFE", "KTO_CAT3", "A05020900"));
            } else if (Set.of("NATURE", "ACTIVITY", "CAFE").contains(normalizedCode)) {
                // The shortlist has already applied the project's confirmed KTO style rules.
                // Keep that result during the compatibility transition even for legacy fixtures
                // that do not carry the matching raw category code.
                result.add(new StyleHint(
                        normalizedCode, "KTO_CONFIRMED_STYLE", normalizedCode));
            }
        }
        return List.copyOf(result);
    }

    private List<CongestionFact> congestionFacts(List<CongestionDto> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<CongestionFact> result = new ArrayList<>();
        for (CongestionDto value : values) {
            if (value == null) {
                continue;
            }
            LocalDate date = parseDate(value.getBaseYmd());
            BigDecimal rate = parseRate(value.getCnctrRate());
            var level = CongestionLevelResolver.resolve(value.getCnctrRate()).orElse(null);
            if (date != null && rate != null && level != null) {
                result.add(new CongestionFact(null, date, rate, level, null));
            }
        }
        return List.copyOf(result);
    }

    private PlacePreferenceDto findMatchingWant(CourseRequestDto request, TourPlaceDto place) {
        if (request == null || request.getCoursePlacePreferences() == null) {
            return null;
        }
        for (PlacePreferenceDto preference : request.getCoursePlacePreferences()) {
            if (preference != null && preference.getPreferenceType() == PreferenceType.WANT
                    && sameNameAndCoordinates(preference, place)) {
                return preference;
            }
        }
        return null;
    }

    private int findMatchingCandidateIndex(
            PlacePreferenceDto preference,
            List<CourseCandidate> candidates
    ) {
        for (int index = 0; index < candidates.size(); index++) {
            CourseCandidate candidate = candidates.get(index);
            if (sameSourceIdentity(preference, candidate)
                    || (candidate.identity().placeId() == null && (
                    sameInternalIdentity(preference, candidate)
                    || sameNameAndCoordinates(preference, candidate)))) {
                return index;
            }
        }
        return -1;
    }

    private boolean sameSourceIdentity(
            PlacePreferenceDto preference,
            CourseCandidate candidate
    ) {
        return !isBlank(preference.getSourceCode())
                && !isBlank(preference.getSourcePlaceId())
                && !isBlank(candidate.identity().sourceCode())
                && !isBlank(candidate.identity().sourcePlaceId())
                && preference.getSourceCode().trim().equalsIgnoreCase(
                        candidate.identity().sourceCode())
                && preference.getSourcePlaceId().trim().equals(
                        candidate.identity().sourcePlaceId());
    }

    private boolean sameInternalIdentity(
            PlacePreferenceDto preference,
            CourseCandidate candidate
    ) {
        return preference.getPlaceId() != null
                && preference.getPlaceId().equals(candidate.identity().placeId());
    }

    private boolean sameNameAndCoordinates(
            PlacePreferenceDto preference,
            CourseCandidate candidate
    ) {
        return sameNameAndCoordinates(
                preference,
                candidate.place().name(),
                candidate.place().latitude(),
                candidate.place().longitude());
    }

    private boolean sameNameAndCoordinates(
            PlacePreferenceDto preference,
            TourPlaceDto place
    ) {
        return sameNameAndCoordinates(
                preference,
                place.getTitle(),
                decimal(place.getLatitude()),
                decimal(place.getLongitude()));
    }

    private boolean sameNameAndCoordinates(
            PlacePreferenceDto preference,
            String name,
            BigDecimal latitude,
            BigDecimal longitude
    ) {
        String preferenceName = normalizeName(preference.getPlaceName());
        if (preferenceName.isEmpty() || !preferenceName.equals(normalizeName(name))) {
            return false;
        }
        if (preference.getLatitude() == null || preference.getLongitude() == null
                || latitude == null || longitude == null) {
            return true;
        }
        return Math.abs(preference.getLatitude() - latitude.doubleValue())
                <= SAME_PLACE_COORDINATE_EPSILON
                && Math.abs(preference.getLongitude() - longitude.doubleValue())
                <= SAME_PLACE_COORDINATE_EPSILON;
    }

    private CourseCandidate withUserConstraint(
            CourseCandidate candidate,
            UserConstraint constraint
    ) {
        return new CourseCandidate(
                candidate.identity(), candidate.place(), constraint,
                candidate.regionCode(), candidate.externalClassifications(),
                candidate.internalPlaceCategory(), candidate.styleHints(),
                candidate.congestionFacts(), candidate.weatherFactSetId());
    }

    private boolean isKakaoPreference(PlacePreferenceDto preference) {
        return KAKAO_SOURCE_CODE.equalsIgnoreCase(preference.getSourceCode())
                && !isBlank(preference.getSourcePlaceId())
                && !isBlank(preference.getPlaceName());
    }

    private String requireCandidateId(String value) {
        if (isBlank(value)) {
            throw new IllegalArgumentException("AI 후보 식별자가 필요합니다.");
        }
        return value;
    }

    private BigDecimal decimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value, CONGESTION_DATE_FORMATTER);
        } catch (DateTimeException | NullPointerException exception) {
            return null;
        }
    }

    private BigDecimal parseRate(String value) {
        try {
            return isBlank(value) ? null : new BigDecimal(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String normalizeName(String value) {
        return value == null
                ? ""
                : value.replaceAll("[\\s\\p{P}\\p{S}]+", "")
                        .toLowerCase(Locale.ROOT);
    }

    private String firstNonBlank(String first, String second) {
        return !isBlank(first) ? first : second;
    }

    private String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    record NormalizationResult(
            List<CourseCandidate> candidates,
            Map<PlacePreferenceDto, String> candidateIdsByPreference
    ) {
    }
}
