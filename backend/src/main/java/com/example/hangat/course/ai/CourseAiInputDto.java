package com.example.hangat.course.ai;

import com.example.hangat.map.model.enums.CongestionLevel;
import com.example.hangat.course.model.GenerationReason;
import com.example.hangat.course.model.PreferenceType;
import com.example.hangat.course.model.Transport;
import com.example.hangat.course.travel.DistanceCalculationMethod;
import com.example.hangat.course.weather.CourseWeatherDto;
import com.example.hangat.course.service.IndoorClassifier;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** Provider-neutral facts exposed at the Gemini input boundary. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CourseAiInputDto(
        String contractVersion,
        TripConstraintDto trip,
        SoftPreferencesDto preferences,
        HardConstraintsDto hardConstraints,
        AccommodationAiFactDto accommodation,
        List<CandidateFactDto> candidates,
        List<WeatherAiFactSetDto> weatherFactSets,
        List<TravelFactDto> travelFacts,
        @JsonIgnore CompatibilityContextDto compatibility
) {

    public CourseAiInputDto {
        candidates = immutableList(candidates);
        weatherFactSets = immutableList(weatherFactSets);
        travelFacts = immutableList(travelFacts);
    }

    /** Compatibility constructor for non-AI downstream callers pending their facts migration. */
    public CourseAiInputDto(
            String contractVersion,
            TripConditionDto tripCondition,
            UserPreferencesDto userPreferences,
            List<CandidateFactDto> candidates,
            List<TravelFactDto> travelFacts,
            GenerationMetadataDto generationMetadata
    ) {
        this(
                contractVersion,
                toTripConstraint(tripCondition),
                toSoftPreferences(userPreferences),
                toHardConstraints(userPreferences),
                toAccommodationAiFact(userPreferences == null ? null : userPreferences.accommodation()),
                candidates,
                List.of(),
                travelFacts,
                new CompatibilityContextDto(tripCondition, userPreferences, generationMetadata));
    }

    @JsonIgnore
    public TripConditionDto tripCondition() {
        return compatibility == null ? null : compatibility.tripCondition();
    }

    @JsonIgnore
    public UserPreferencesDto userPreferences() {
        return compatibility == null ? null : compatibility.userPreferences();
    }

    @JsonIgnore
    public GenerationMetadataDto generationMetadata() {
        return compatibility == null ? null : compatibility.generationMetadata();
    }

    private static TripConstraintDto toTripConstraint(TripConditionDto trip) {
        return trip == null ? null : new TripConstraintDto(
                trip.startDate(), trip.endDate(), trip.transport());
    }

    private static SoftPreferencesDto toSoftPreferences(UserPreferencesDto preferences) {
        if (preferences == null) {
            return new SoftPreferencesDto(List.of(), List.of());
        }
        return new SoftPreferencesDto(
                preferences.selectedRegions().stream().map(SelectedRegionDto::code).toList(),
                preferences.selectedStyles().stream().map(SelectedStyleDto::code).toList());
    }

    private static HardConstraintsDto toHardConstraints(UserPreferencesDto preferences) {
        if (preferences == null) {
            return new HardConstraintsDto(List.of());
        }
        return new HardConstraintsDto(preferences.requiredPlaces().stream()
                .filter(required -> required.identity() != null)
                .map(required -> new RequiredCandidateConstraintDto(
                        required.identity().candidateId(),
                        required.fixedDate(),
                        required.fixedTime()))
                .toList());
    }

    private static AccommodationAiFactDto toAccommodationAiFact(
            AccommodationFactDto accommodation
    ) {
        return accommodation == null
                ? null
                : new AccommodationAiFactDto(
                        "accommodation-1", accommodation.name(), accommodation.regionCode());
    }

    private static <T> List<T> immutableList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TripConstraintDto(
            LocalDate startDate,
            LocalDate endDate,
            Transport transport
    ) {
    }

    public record SoftPreferencesDto(
            List<String> selectedRegionCodes,
            List<String> selectedStyleCodes
    ) {
        public SoftPreferencesDto {
            selectedRegionCodes = immutableList(selectedRegionCodes);
            selectedStyleCodes = immutableList(selectedStyleCodes);
        }
    }

    public record HardConstraintsDto(
            List<RequiredCandidateConstraintDto> requiredCandidates
    ) {
        public HardConstraintsDto {
            requiredCandidates = immutableList(requiredCandidates);
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RequiredCandidateConstraintDto(
            String candidateId,
            LocalDate fixedDate,
            LocalTime fixedTime
    ) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AccommodationAiFactDto(
            String accommodationRef,
            String name,
            String regionCode
    ) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CandidateFactDto(
            String candidateId,
            String name,
            String regionCode,
            String internalCategoryCode,
            List<String> styleHintCodes,
            List<CongestionFactDto> congestionFacts,
            String weatherFactSetId,
            @JsonIgnore CandidateCompatibilityDto compatibility
    ) {
        public CandidateFactDto {
            styleHintCodes = immutableList(styleHintCodes);
            congestionFacts = immutableList(congestionFacts);
        }

        /**
         * 실내 여부 - 이름 키워드 휴리스틱({@link IndoorClassifier}). DB 컬럼이 아니라 이름에서 파생하므로
         * 생성자 인자로 두지 않고 직렬화 때만 붙인다(읽기 전용). 시스템 프롬프트의 "indoor=true 후보"가 이 값이고,
         * 규칙 기반 폴백도 같은 값으로 비 예보일 실내 우선을 판단한다.
         */
        @JsonProperty(value = "indoor", access = JsonProperty.Access.READ_ONLY)
        public boolean indoor() {
            return IndoorClassifier.isIndoor(name);
        }

        /** Compatibility constructor for persistence/response tests during downstream migration. */
        public CandidateFactDto(
                PlaceIdentityDto identity,
                String name,
                String address,
                Double latitude,
                Double longitude,
                TourCategoryDto tourCategory,
                String regionCode,
                PreferenceType preferenceType,
                List<String> confirmedStyleHints,
                List<CongestionFactDto> congestion,
                List<CourseWeatherDto> weather
        ) {
            this(
                    identity == null ? null : identity.candidateId(),
                    name,
                    regionCode,
                    null,
                    confirmedStyleHints,
                    congestion,
                    null,
                    new CandidateCompatibilityDto(
                            identity, address, latitude, longitude, tourCategory,
                            preferenceType, confirmedStyleHints, congestion, weather));
        }

        @JsonIgnore
        public PlaceIdentityDto identity() {
            return compatibility == null
                    ? new PlaceIdentityDto(candidateId, null, null, null)
                    : compatibility.identity();
        }

        @JsonIgnore
        public String address() {
            return compatibility == null ? null : compatibility.address();
        }

        @JsonIgnore
        public Double latitude() {
            return compatibility == null ? null : compatibility.latitude();
        }

        @JsonIgnore
        public Double longitude() {
            return compatibility == null ? null : compatibility.longitude();
        }

        @JsonIgnore
        public TourCategoryDto tourCategory() {
            return compatibility == null ? null : compatibility.tourCategory();
        }

        @JsonIgnore
        public PreferenceType preferenceType() {
            return compatibility == null ? null : compatibility.preferenceType();
        }

        @JsonIgnore
        public List<String> confirmedStyleHints() {
            return compatibility == null ? styleHintCodes : compatibility.confirmedStyleHints();
        }

        @JsonIgnore
        public List<CongestionFactDto> congestion() {
            return compatibility == null ? congestionFacts : compatibility.congestion();
        }

        @JsonIgnore
        public List<CourseWeatherDto> weather() {
            return compatibility == null ? null : compatibility.weather();
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CongestionFactDto(
            LocalDate date,
            BigDecimal rate,
            CongestionLevel level
    ) {
    }

    public record WeatherAiFactSetDto(
            String weatherFactSetId,
            List<WeatherAiFactDto> facts
    ) {
        public WeatherAiFactSetDto {
            facts = immutableList(facts);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WeatherAiFactDto(
            LocalDate forecastDate,
            LocalTime forecastTime,
            BigDecimal temperature,
            Integer precipitationProbability,
            String precipitationTypeCode,
            String skyConditionCode,
            BigDecimal windSpeed,
            com.example.hangat.course.facts.DailyWeatherEvidence dailyEvidence
    ) {
        public WeatherAiFactDto(LocalDate date, LocalTime time, BigDecimal temperature, Integer probability,
                String precipitation, String sky, BigDecimal wind) {
            this(date, time, temperature, probability, precipitation, sky, wind, null);
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TravelFactDto(
            String fromRef,
            String toRef,
            BigDecimal straightDistanceMeters,
            BigDecimal routeDistanceMeters,
            Integer travelMinutes,
            Transport transport,
            @JsonIgnore TravelCompatibilityDto compatibility
    ) {
        /** Compatibility constructor for legacy fixtures pending downstream migration. */
        public TravelFactDto(
                String fromCandidateId,
                String fromTitle,
                String toCandidateId,
                String toTitle,
                BigDecimal straightDistanceKm,
                DistanceCalculationMethod straightDistanceMethod,
                BigDecimal routeDistanceKm,
                Integer durationMinutes,
                Transport transport,
                String routeSourceCode,
                Instant routeCalculatedAt
        ) {
            this(
                    fromCandidateId,
                    toCandidateId,
                    toMeters(straightDistanceKm),
                    toMeters(routeDistanceKm),
                    durationMinutes,
                    transport,
                    new TravelCompatibilityDto(
                            fromTitle, toTitle, straightDistanceMethod,
                            routeSourceCode, routeCalculatedAt));
        }

        @JsonIgnore
        public String fromCandidateId() {
            return fromRef;
        }

        @JsonIgnore
        public String toCandidateId() {
            return toRef;
        }

        @JsonIgnore
        public String fromTitle() {
            return compatibility == null ? null : compatibility.fromTitle();
        }

        @JsonIgnore
        public String toTitle() {
            return compatibility == null ? null : compatibility.toTitle();
        }

        @JsonIgnore
        public BigDecimal straightDistanceKm() {
            return toKilometers(straightDistanceMeters);
        }

        @JsonIgnore
        public DistanceCalculationMethod straightDistanceMethod() {
            return compatibility == null ? null : compatibility.straightDistanceMethod();
        }

        @JsonIgnore
        public BigDecimal routeDistanceKm() {
            return toKilometers(routeDistanceMeters);
        }

        @JsonIgnore
        public Integer durationMinutes() {
            return travelMinutes;
        }

        @JsonIgnore
        public String routeSourceCode() {
            return compatibility == null ? null : compatibility.routeSourceCode();
        }

        @JsonIgnore
        public Instant routeCalculatedAt() {
            return compatibility == null ? null : compatibility.routeCalculatedAt();
        }

        private static BigDecimal toMeters(BigDecimal kilometers) {
            return kilometers == null ? null : kilometers.movePointRight(3);
        }

        private static BigDecimal toKilometers(BigDecimal meters) {
            return meters == null ? null : meters.movePointLeft(3);
        }
    }

    /* Compatibility-only types below are deliberately excluded from the Gemini JSON. */

    public record SelectedRegionDto(Long regionId, String code, String name) {
    }

    public record SelectedStyleDto(Long tagId, String code, String name, BigDecimal weight) {
    }

    public record PlaceIdentityDto(
            String candidateId,
            Long placeId,
            String sourceCode,
            String sourcePlaceId
    ) {
    }

    public record PlaceConstraintDto(
            PlaceIdentityDto identity,
            String name,
            String address,
            Double latitude,
            Double longitude,
            String categoryName,
            PreferenceType preferenceType,
            LocalDate fixedDate,
            LocalTime fixedTime
    ) {
    }

    public record AccommodationFactDto(
            PlaceIdentityDto identity,
            String name,
            String address,
            String roadAddress,
            Double latitude,
            Double longitude,
            String categoryName,
            String regionCode
    ) {
    }

    public record TripConditionDto(
            LocalDate startDate,
            LocalDate endDate,
            Integer people,
            Transport transport
    ) {
    }

    public record UserPreferencesDto(
            List<SelectedRegionDto> selectedRegions,
            List<SelectedStyleDto> selectedStyles,
            List<PlaceConstraintDto> requiredPlaces,
            List<PlaceConstraintDto> forbiddenPlaces,
            AccommodationFactDto accommodation
    ) {
        public UserPreferencesDto {
            selectedRegions = immutableList(selectedRegions);
            selectedStyles = immutableList(selectedStyles);
            requiredPlaces = immutableList(requiredPlaces);
            forbiddenPlaces = immutableList(forbiddenPlaces);
        }
    }

    public record TourCategoryDto(String category1, String category2, String category3) {
    }

    public record GenerationMetadataDto(
            GenerationReason generationReason,
            String algorithmVersion,
            String requestReference
    ) {
    }

    public record CompatibilityContextDto(
            TripConditionDto tripCondition,
            UserPreferencesDto userPreferences,
            GenerationMetadataDto generationMetadata
    ) {
    }

    public record CandidateCompatibilityDto(
            PlaceIdentityDto identity,
            String address,
            Double latitude,
            Double longitude,
            TourCategoryDto tourCategory,
            PreferenceType preferenceType,
            List<String> confirmedStyleHints,
            List<CongestionFactDto> congestion,
            List<CourseWeatherDto> weather
    ) {
        public CandidateCompatibilityDto {
            confirmedStyleHints = immutableList(confirmedStyleHints);
            congestion = immutableList(congestion);
            weather = weather == null ? null : List.copyOf(weather);
        }
    }

    public record TravelCompatibilityDto(
            String fromTitle,
            String toTitle,
            DistanceCalculationMethod straightDistanceMethod,
            String routeSourceCode,
            Instant routeCalculatedAt
    ) {
    }
}
