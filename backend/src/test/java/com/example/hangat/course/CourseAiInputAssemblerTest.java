package com.example.hangat.course;

import com.example.hangat.course.ai.CourseAiInputDto;
import com.example.hangat.course.ai.CourseAiInputDto.GenerationMetadataDto;
import com.example.hangat.course.facts.WeatherFact;
import com.example.hangat.course.facts.WeatherFactSet;
import com.example.hangat.course.model.CongestionDto;
import com.example.hangat.course.model.CourseCandidateDto;
import com.example.hangat.course.model.CourseRequestDto;
import com.example.hangat.course.model.GenerationReason;
import com.example.hangat.course.model.PreferenceType;
import com.example.hangat.course.model.TourPlaceDto;
import com.example.hangat.course.model.Transport;
import com.example.hangat.course.travel.CourseTravelLegDto;
import com.example.hangat.course.travel.DistanceCalculationMethod;
import com.example.hangat.course.weather.CourseWeatherDto;
import com.example.hangat.course.weather.CourseWeatherFacts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CourseAiInputAssemblerTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final CourseAiInputAssembler assembler = new CourseAiInputAssembler();

    @Test
    void marksIndoorCandidatesByNameKeywordForRainyDayPlacement() throws Exception {
        // 실내 여부는 컬럼이 아니라 이름 키워드 휴리스틱(IndoorClassifier) - 배치 코스와 같은 목록으로 Gemini 입력에 실린다
        CourseRequestDto request = kakaoWantRequest(
                "사용자 지정 카페", "KAKAO-777", "제주특별자치도 제주시 구좌읍 월정리 1");
        CourseCandidateDto outdoor = new CourseCandidateDto(
                tourPlace("125266", "비자림", "제주특별자치도 제주시 구좌읍 비자숲길 55"),
                List.of(congestion("20260827", "42.50")), null, List.of("NATURE"));
        CourseCandidateDto indoor = new CourseCandidateDto(
                tourPlace("125267", "제주민속자연사박물관", "제주특별자치도 제주시 삼성로 40"),
                List.of(congestion("20260827", "35.00")), null, List.of("CULTURE"));
        CourseGenerationFactsAssembler.Assembly facts = new CourseGenerationFactsAssembler()
                .assemble(request, List.of(outdoor, indoor),
                        new CourseWeatherFacts(Map.of(), List.of()), List.of());

        CourseAiInputDto result = assembler.assemble(
                request, facts, new GenerationMetadataDto(GenerationReason.INITIAL, "course-ai-v2", "ref"));
        JsonNode candidates = objectMapper.readTree(objectMapper.writeValueAsString(result)).path("candidates");

        Map<String, Boolean> indoorByName = new java.util.HashMap<>();
        candidates.forEach(node -> indoorByName.put(node.path("name").asText(), node.path("indoor").asBoolean()));
        assertThat(indoorByName)
                .containsEntry("비자림", false)
                .containsEntry("제주민속자연사박물관", true)
                .containsEntry("사용자 지정 카페", true);
        candidates.forEach(node -> assertThat(node.path("indoor").isBoolean()).isTrue());
    }

    @Test
    void serializesProviderNeutralProductionContractFromGenerationFacts() throws Exception {
        CourseRequestDto request = kakaoWantRequest(
                "사용자 지정 카페",
                "KAKAO-777",
                "제주특별자치도 제주시 구좌읍 월정리 1");
        CourseCandidateDto ktoCandidate = new CourseCandidateDto(
                tourPlace("125266", "비자림", "제주특별자치도 제주시 구좌읍 비자숲길 55"),
                List.of(
                        congestion("20260827", "42.50"),
                        congestion("20260901", "99.00")),
                null, List.of("NATURE"));
        WeatherFactSet weatherSet = new WeatherFactSet(
                "weather-east-1", "KMA", 55, 38,
                LocalDate.of(2026, 8, 27), LocalTime.of(5, 0),
                List.of(
                        new WeatherFact(
                                91L, LocalDate.of(2026, 8, 27), LocalTime.of(14, 0),
                                new BigDecimal("27.0"), 30, "0", "3",
                                new BigDecimal("2.5"), 65),
                        new WeatherFact(
                                92L, LocalDate.of(2026, 9, 1), LocalTime.of(14, 0),
                                new BigDecimal("29.0"), 10, "0", "1",
                                new BigDecimal("1.5"), 60)));
        CourseTravelLegDto travel = new CourseTravelLegDto(
                "125266", "비자림", "request-want-1", "사용자 지정 카페",
                new BigDecimal("8.2"), DistanceCalculationMethod.HAVERSINE,
                null, null, Transport.RENTAL_CAR, null, null);
        CourseGenerationFactsAssembler.Assembly facts = new CourseGenerationFactsAssembler()
                .assemble(
                        request,
                        List.of(ktoCandidate),
                        new CourseWeatherFacts(
                                Map.of(
                                        "125266", "weather-east-1",
                                        "request-want-1", "weather-east-1"),
                                List.of(weatherSet)),
                        List.of(travel));

        CourseAiInputDto result = assembler.assemble(
                request,
                facts,
                new GenerationMetadataDto(
                        GenerationReason.INITIAL, "course-ai-v2", "request-secret-ref"));
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(result));

        assertThat(json.path("contractVersion").asText()).isEqualTo("2.0");
        assertThat(json.path("trip").path("startDate").asText()).isEqualTo("2026-08-27");
        assertThat(json.path("trip").path("endDate").asText()).isEqualTo("2026-08-29");
        assertThat(json.path("trip").path("transport").asText()).isEqualTo("RENTAL_CAR");
        assertThat(json.path("preferences").path("selectedRegionCodes").get(0).asText())
                .isEqualTo("EAST");
        assertThat(json.path("preferences").path("selectedStyleCodes").get(0).asText())
                .isEqualTo("NATURE");
        assertThat(json.path("hardConstraints").path("requiredCandidates").get(0)
                .path("candidateId").asText()).isEqualTo("request-want-1");
        assertThat(json.path("hardConstraints").path("requiredCandidates").get(0)
                .path("fixedDate").asText()).isEqualTo("2026-08-27");
        assertThat(json.path("hardConstraints").path("requiredCandidates").get(0)
                .path("fixedTime").asText()).isEqualTo("10:30:00");
        assertThat(json.path("candidates")).hasSize(2);
        assertThat(json.path("candidates").get(0).path("candidateId").asText())
                .isEqualTo("125266");
        assertThat(json.path("candidates").get(0).path("name").asText()).isEqualTo("비자림");
        assertThat(json.path("candidates").get(0).path("regionCode").asText())
                .isEqualTo("EAST");
        assertThat(json.path("candidates").get(0).path("internalCategoryCode").asText())
                .isEqualTo("TOURIST");
        assertThat(json.path("candidates").get(0).path("styleHintCodes").get(0).asText())
                .isEqualTo("NATURE");
        assertThat(json.path("candidates").get(0).path("congestionFacts").get(0)
                .path("rate").decimalValue()).isEqualByComparingTo("42.50");
        assertThat(json.path("candidates").get(0).path("congestionFacts")).hasSize(1);
        assertThat(json.path("candidates").get(0).path("weatherFactSetId").asText())
                .isEqualTo("weather-east-1");
        assertThat(json.path("candidates").get(1).path("candidateId").asText())
                .isEqualTo("request-want-1");
        assertThat(json.path("candidates").get(1).path("internalCategoryCode").asText())
                .isEqualTo("TOURIST");
        assertThat(json.path("candidates").get(1).path("congestionFacts")).isEmpty();
        assertThat(json.path("weatherFactSets")).hasSize(1);
        assertThat(json.path("weatherFactSets").get(0).path("weatherFactSetId").asText())
                .isEqualTo("weather-east-1");
        assertThat(json.path("weatherFactSets").get(0).path("facts").get(0)
                .path("forecastTime").asText()).isEqualTo("14:00:00");
        assertThat(json.path("weatherFactSets").get(0).path("facts").get(0)
                .path("temperature").decimalValue()).isEqualByComparingTo("27.0");
        assertThat(json.path("weatherFactSets").get(0).path("facts")).hasSize(1);
        assertThat(json.path("travelFacts")).hasSize(1);
        assertThat(json.path("travelFacts").get(0).path("fromRef").asText())
                .isEqualTo("125266");
        assertThat(json.path("travelFacts").get(0).path("toRef").asText())
                .isEqualTo("request-want-1");
        assertThat(json.path("travelFacts").get(0).path("straightDistanceMeters")
                .decimalValue()).isEqualByComparingTo("8200");
        assertThat(json.path("travelFacts").get(0).path("routeDistanceMeters").isNull())
                .isTrue();
        assertThat(json.path("travelFacts").get(0).path("travelMinutes").isNull()).isTrue();
        assertThat(json.toString()).doesNotContain(
                "placeId", "sourceCode", "sourcePlaceId", "identity",
                "externalClassifications", "tourCategory", "weather\"",
                "straightDistanceKm", "routeDistanceKm", "durationMinutes",
                "gridX", "gridY", "baseDate", "baseTime", "humidity",
                "generationMetadata", "algorithmVersion", "request-secret-ref");
    }

    @Test
    void assemblesSeparatedConditionsFactsAndMissingRouteData() throws Exception {
        CourseRequestDto request = request();
        CourseCandidateDto wantCandidate = new CourseCandidateDto(
                tourPlace("125266", "비자림", "제주특별자치도 제주시 구좌읍 비자숲길 55"),
                List.of(congestion("20260827", "72.50")),
                PreferenceType.WANT,
                List.of("NATURE"));
        CourseCandidateDto ordinaryCandidate = new CourseCandidateDto(
                tourPlace("126450", "성산일출봉", "제주특별자치도 서귀포시 성산읍 일출로 284-12"),
                List.of(),
                null,
                List.of("NATURE", "ACTIVITY"));
        CourseCandidateDto avoidCandidate = new CourseCandidateDto(
                tourPlace("999999", "제외 장소", "제주특별자치도 제주시"),
                List.of(),
                PreferenceType.AVOID,
                List.of());
        CourseWeatherDto weather = new CourseWeatherDto(
                LocalDate.of(2026, 8, 27), LocalTime.of(12, 0),
                new BigDecimal("28.5"), 30, "0", "1",
                new BigDecimal("3.2"), 70);
        CourseTravelLegDto travel = new CourseTravelLegDto(
                "125266", "비자림", "126450", "성산일출봉",
                new BigDecimal("12.345"), DistanceCalculationMethod.HAVERSINE,
                null, null, Transport.RENTAL_CAR, null, null);

        CourseAiInputDto result = assembler.assemble(
                request,
                List.of(wantCandidate, ordinaryCandidate, avoidCandidate),
                Map.of("125266", List.of(weather)),
                List.of(travel),
                new GenerationMetadataDto(GenerationReason.INITIAL, "course-ai-v1", "request-1"));

        assertThat(result.tripCondition().startDate()).isEqualTo(LocalDate.of(2026, 8, 27));
        assertThat(result.tripCondition().people()).isEqualTo(2);
        assertThat(result.tripCondition().transport()).isEqualTo(Transport.RENTAL_CAR);
        assertThat(result.userPreferences().selectedRegions()).extracting("code").containsExactly("EAST");
        assertThat(result.userPreferences().selectedStyles()).extracting("code").containsExactly("NATURE");
        assertThat(result.userPreferences().selectedStyles().get(0).weight())
                .isEqualByComparingTo("0.8");

        assertThat(result.userPreferences().requiredPlaces()).hasSize(1);
        assertThat(result.userPreferences().requiredPlaces().get(0).preferenceType())
                .isEqualTo(PreferenceType.WANT);
        assertThat(result.userPreferences().requiredPlaces().get(0).fixedDate())
                .isEqualTo(LocalDate.of(2026, 8, 27));
        assertThat(result.userPreferences().requiredPlaces().get(0).fixedTime())
                .isEqualTo(LocalTime.of(10, 30));
        assertThat(result.userPreferences().forbiddenPlaces()).hasSize(1);
        assertThat(result.userPreferences().forbiddenPlaces().get(0).preferenceType())
                .isEqualTo(PreferenceType.AVOID);

        assertThat(result.candidates()).hasSize(2);
        CourseAiInputDto.CandidateFactDto first = result.candidates().get(0);
        assertThat(first.identity().candidateId()).isEqualTo("125266");
        assertThat(first.identity().placeId()).isNull();
        assertThat(first.identity().sourceCode()).isEqualTo("KTO");
        assertThat(first.identity().sourcePlaceId()).isEqualTo("125266");
        assertThat(first.preferenceType()).isEqualTo(PreferenceType.WANT);
        assertThat(first.regionCode()).isEqualTo("EAST");
        assertThat(first.confirmedStyleHints()).containsExactly("NATURE");
        assertThat(first.congestion().get(0).rate()).isEqualByComparingTo("72.50");
        assertThat(first.congestion().get(0).level().name()).isEqualTo("CROWDED");
        assertThat(first.weather()).containsExactly(weather);

        CourseAiInputDto.CandidateFactDto second = result.candidates().get(1);
        assertThat(second.confirmedStyleHints()).containsExactly("NATURE", "ACTIVITY");
        assertThat(second.congestion()).isEmpty();
        assertThat(second.weather()).isNull();

        assertThat(result.travelFacts()).hasSize(1);
        assertThat(result.travelFacts().get(0).straightDistanceKm())
                .isEqualByComparingTo("12.345");
        assertThat(result.travelFacts().get(0).routeDistanceKm()).isNull();
        assertThat(result.travelFacts().get(0).durationMinutes()).isNull();

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(result));
        assertThat(json.path("trip").path("startDate").asText()).isEqualTo("2026-08-27");
        assertThat(json.path("preferences").path("selectedStyleCodes").get(0).asText())
                .isEqualTo("NATURE");
        assertThat(json.path("hardConstraints").path("requiredCandidates")
                .get(0).path("candidateId").asText()).isEqualTo("125266");
        assertThat(json.path("candidates").get(0).path("styleHintCodes").get(0).asText())
                .isEqualTo("NATURE");
        assertThat(json.path("candidates").get(0).path("congestionFacts")
                .get(0).path("rate").decimalValue())
                .isEqualByComparingTo("72.50");
        assertThat(json.path("candidates").get(1).path("weatherFactSetId").isNull()).isTrue();
        assertThat(json.path("travelFacts").get(0).path("routeDistanceMeters").isNull()).isTrue();
        assertThat(json.path("travelFacts").get(0).path("travelMinutes").isNull()).isTrue();
        assertThat(json.has("tripCondition")).isFalse();
        assertThat(json.has("userPreferences")).isFalse();
        assertThat(json.has("generationMetadata")).isFalse();
    }

    @Test
    void keepsInternalAndExternalPreferenceIdsSeparate() throws Exception {
        CourseAiInputDto result = assembler.assemble(
                request(), List.of(), Map.of(), List.of(),
                new GenerationMetadataDto(GenerationReason.INITIAL, null, null));

        CourseAiInputDto.PlaceIdentityDto wantIdentity =
                result.userPreferences().requiredPlaces().get(0).identity();
        CourseAiInputDto.PlaceIdentityDto avoidIdentity =
                result.userPreferences().forbiddenPlaces().get(0).identity();

        assertThat(wantIdentity.placeId()).isEqualTo(101L);
        assertThat(wantIdentity.sourceCode()).isNull();
        assertThat(wantIdentity.sourcePlaceId()).isNull();
        assertThat(avoidIdentity.placeId()).isNull();
        assertThat(avoidIdentity.sourceCode()).isEqualTo("KAKAO_LOCAL");
        assertThat(avoidIdentity.sourcePlaceId()).isEqualTo("external-9");
    }

    @Test
    void representsUnknownCongestionAndUnknownRegionWithoutInventingFacts() throws Exception {
        CourseCandidateDto candidate = new CourseCandidateDto(
                tourPlace("unknown-1", "미분류 장소", "주소 미상"),
                List.of(congestion("bad-date", "not-a-number")),
                PreferenceType.WANT,
                List.of());

        CourseAiInputDto result = assembler.assemble(
                request(), List.of(candidate), Map.of(), List.of(),
                new GenerationMetadataDto(GenerationReason.INITIAL, null, null));

        assertThat(result.candidates().get(0).regionCode()).isEqualTo("UNKNOWN");
        assertThat(result.candidates().get(0).congestion().get(0).date()).isNull();
        assertThat(result.candidates().get(0).congestion().get(0).rate()).isNull();
        assertThat(result.candidates().get(0).congestion().get(0).level()).isNull();
    }

    @Test
    void addsUnmatchedKakaoWantAsRequestScopedCandidateWithoutInventingFacts()
            throws Exception {
        CourseRequestDto request = kakaoWantRequest(
                "카카오 비자림 입구",
                "KAKAO-777",
                "제주특별자치도 제주시 애월읍 애월로 1");
        CourseCandidateDto ktoCandidate = new CourseCandidateDto(
                tourPlace("125266", "비자림", "제주특별자치도 제주시 구좌읍 비자숲길 55"),
                List.of(), null, List.of("NATURE"));

        CourseAiInputDto result = assembler.assemble(
                request, List.of(ktoCandidate), Map.of(), List.of(),
                new GenerationMetadataDto(GenerationReason.INITIAL, null, null));

        assertThat(result.candidates()).hasSize(2);
        CourseAiInputDto.CandidateFactDto kakao = result.candidates().stream()
                .filter(candidate -> "KAKAO_LOCAL".equals(candidate.identity().sourceCode()))
                .findFirst()
                .orElseThrow();
        assertThat(kakao.identity().candidateId()).isEqualTo("request-want-1");
        assertThat(kakao.identity().sourcePlaceId()).isEqualTo("KAKAO-777");
        assertThat(kakao.name()).isEqualTo("카카오 비자림 입구");
        assertThat(kakao.regionCode()).isEqualTo("WEST");
        assertThat(kakao.preferenceType()).isEqualTo(PreferenceType.WANT);
        assertThat(kakao.tourCategory()).isNull();
        assertThat(kakao.confirmedStyleHints()).isEmpty();
        assertThat(kakao.congestion()).isEmpty();
        assertThat(kakao.weather()).isNull();

        CourseAiInputDto.PlaceConstraintDto required =
                result.userPreferences().requiredPlaces().get(0);
        assertThat(required.identity().candidateId()).isEqualTo("request-want-1");
        assertThat(required.identity().sourceCode()).isEqualTo("KAKAO_LOCAL");
        assertThat(required.categoryName()).isEqualTo("여행 > 관광,명소 > 자연명소");
        assertThat(required.fixedDate()).isEqualTo(LocalDate.of(2026, 8, 27));
        assertThat(required.fixedTime()).isEqualTo(LocalTime.of(10, 30));
    }

    @Test
    void replacesEquivalentKtoCandidateWithKakaoWantWithoutDuplicateCandidate()
            throws Exception {
        CourseRequestDto request = kakaoWantRequest(
                "비자림",
                "KAKAO-125266",
                "제주특별자치도 제주시 구좌읍 비자숲길 55");
        CourseCandidateDto ktoCandidate = new CourseCandidateDto(
                tourPlace("125266", "비자림", "제주특별자치도 제주시 구좌읍 비자숲길 55"),
                List.of(congestion("20260827", "22.5")), null, List.of("NATURE"));

        CourseAiInputDto result = assembler.assemble(
                request, List.of(ktoCandidate), Map.of(), List.of(),
                new GenerationMetadataDto(GenerationReason.INITIAL, null, null));

        assertThat(result.candidates()).hasSize(1);
        CourseAiInputDto.CandidateFactDto candidate = result.candidates().get(0);
        assertThat(candidate.identity().candidateId()).isEqualTo("125266");
        assertThat(candidate.identity().sourceCode()).isEqualTo("KAKAO_LOCAL");
        assertThat(candidate.identity().sourcePlaceId()).isEqualTo("KAKAO-125266");
        assertThat(candidate.tourCategory()).isNull();
        assertThat(candidate.congestion()).isEmpty();
        assertThat(candidate.weather()).isNull();
        assertThat(result.userPreferences().requiredPlaces().get(0)
                .identity().candidateId()).isEqualTo("125266");
    }

    @Test
    void rejectsTravelFactWithUnknownFromCandidate() throws Exception {
        assertUnknownTravelReference("missing", "125266");
    }

    @Test
    void rejectsTravelFactWithUnknownToCandidate() throws Exception {
        assertUnknownTravelReference("125266", "missing");
    }

    @Test
    void rejectsTravelFactWithMissingCandidateId() throws Exception {
        assertUnknownTravelReference(null, "125266");
    }

    private void assertUnknownTravelReference(String fromCandidateId, String toCandidateId)
            throws Exception {
        CourseCandidateDto candidate = new CourseCandidateDto(
                tourPlace("125266", "비자림", "제주특별자치도 제주시 구좌읍 비자숲길 55"),
                List.of(), null, List.of("NATURE"));
        CourseTravelLegDto travel = new CourseTravelLegDto(
                fromCandidateId, "출발지", toCandidateId, "도착지",
                new BigDecimal("1.0"), DistanceCalculationMethod.HAVERSINE,
                null, null, Transport.RENTAL_CAR, null, null);

        assertThatThrownBy(() -> assembler.assemble(
                request(), List.of(candidate), Map.of(), List.of(travel),
                new GenerationMetadataDto(GenerationReason.INITIAL, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private CourseRequestDto request() throws Exception {
        return objectMapper.readValue("""
                {
                  "start_date": "2026-08-27",
                  "end_date": "2026-08-29",
                  "people": 2,
                  "transport": "RENTAL_CAR",
                  "course_regions": [
                    {"region_id": 2, "code": "EAST", "name": "동부"}
                  ],
                  "course_styles": [
                    {"tag_id": 1, "code": "NATURE", "name": "자연", "weight": 0.8}
                  ],
                  "course_place_preferences": [
                    {
                      "place_id": 101,
                      "place_name": "비자림",
                      "preference_type": "WANT",
                      "fixed_date": "2026-08-27",
                      "fixed_time": "10:30"
                    },
                    {
                      "source_code": "KAKAO_LOCAL",
                      "source_place_id": "external-9",
                      "place_name": "제외 장소",
                      "preference_type": "AVOID"
                    }
                  ],
                  "accommodation": {
                    "source_code": "KAKAO_LOCAL",
                    "source_place_id": "lodging-1",
                    "place_name": "숙소",
                    "latitude": 33.4,
                    "longitude": 126.5,
                    "region": "EAST"
                  }
                }
                """, CourseRequestDto.class);
    }

    private CourseRequestDto kakaoWantRequest(
            String placeName,
            String sourcePlaceId,
            String address
    ) throws Exception {
        return objectMapper.readValue("""
                {
                  "start_date": "2026-08-27",
                  "end_date": "2026-08-29",
                  "people": 2,
                  "transport": "RENTAL_CAR",
                  "course_regions": [
                    {"region_id": 2, "code": "EAST", "name": "동부"}
                  ],
                  "course_styles": [
                    {"tag_id": 1, "code": "NATURE", "name": "자연", "weight": 0.8}
                  ],
                  "course_place_preferences": [
                    {
                      "source_code": "KAKAO_LOCAL",
                      "source_place_id": "%s",
                      "place_name": "%s",
                      "road_address": "%s",
                      "latitude": 33.485,
                      "longitude": 126.811,
                      "category_name": "여행 > 관광,명소 > 자연명소",
                      "preference_type": "WANT",
                      "fixed_date": "2026-08-27",
                      "fixed_time": "10:30"
                    }
                  ]
                }
                """.formatted(sourcePlaceId, placeName, address), CourseRequestDto.class);
    }

    private TourPlaceDto tourPlace(String contentId, String title, String address) throws Exception {
        return objectMapper.readValue("""
                {
                  "contentid": "%s",
                  "title": "%s",
                  "addr1": "%s",
                  "mapy": 33.485,
                  "mapx": 126.811,
                  "cat1": "A01",
                  "cat2": "A0101",
                  "cat3": "A01010100"
                }
                """.formatted(contentId, title, address), TourPlaceDto.class);
    }

    private CongestionDto congestion(String date, String rate) throws Exception {
        return objectMapper.readValue("""
                {"baseYmd": "%s", "cnctrRate": "%s"}
                """.formatted(date, rate), CongestionDto.class);
    }
}
