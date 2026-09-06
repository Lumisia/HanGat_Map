package com.example.hangat.course;

import com.example.hangat.course.model.CourseRequestDto;
import com.example.hangat.course.model.PreferenceType;
import com.example.hangat.course.model.TourPlaceDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CourseCandidateShortlistServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());
    private final CourseCandidateShortlistService service =
            new CourseCandidateShortlistService();

    @Test
    void limitsOrdinaryCandidatesWhileKeepingWantAndExcludingAvoidAndOtherRegions()
            throws Exception {
        List<TourPlaceDto> raw = new ArrayList<>();
        raw.add(place("want", "서부 WANT", "제주특별자치도 제주시 애월읍", "A01"));
        raw.add(place("avoid", "제외 장소", "제주특별자치도 제주시 구좌읍", "A01"));
        raw.add(place("west", "서부 일반", "제주특별자치도 제주시 한림읍", "A01"));
        raw.add(place("unknown", "주소 불명", "주소 미상", "A01"));
        for (int index = 0; index < 40; index++) {
            raw.add(place("east-" + index, "동부 장소 " + index,
                    "제주특별자치도 제주시 구좌읍", index % 2 == 0 ? "A01" : "A02"));
        }

        List<CourseCandidateShortlistService.ShortlistedPlace> result = service.select(
                request("""
                        [{"code":"EAST","name":"동부"}]
                        """, """
                        [
                          {"place_name":"서부 WANT","preference_type":"WANT"},
                          {"place_name":"제외 장소","preference_type":"AVOID"}
                        ]
                        """),
                raw);

        assertThat(result).hasSize(15);
        assertThat(result).anySatisfy(candidate -> {
            assertThat(candidate.place().getContentId()).isEqualTo("want");
            assertThat(candidate.preferenceType()).isEqualTo(PreferenceType.WANT);
        });
        assertThat(result).extracting(candidate -> candidate.place().getContentId())
                .doesNotContain("avoid", "west", "unknown");
    }

    @Test
    void keepsCandidatesWithoutStyleHintsBecauseStyleIsSoft() throws Exception {
        TourPlaceDto nature = place(
                "nature", "자연 장소", "제주특별자치도 제주시 구좌읍", "A01");
        TourPlaceDto noHint = place(
                "no-hint", "문화 장소", "제주특별자치도 제주시 구좌읍", "A02");

        List<CourseCandidateShortlistService.ShortlistedPlace> result = service.select(
                request("[]", "[]"), List.of(nature, noHint));

        assertThat(result).extracting(candidate -> candidate.place().getContentId())
                .containsExactly("nature", "no-hint");
    }

    @Test
    void wantCandidatesRemainEvenWhenTheyExceedNormalShortlistLimit() throws Exception {
        List<TourPlaceDto> raw = new ArrayList<>();
        StringBuilder preferences = new StringBuilder("[");
        for (int index = 0; index < 16; index++) {
            if (index > 0) {
                preferences.append(',');
            }
            String name = "필수 장소 " + index;
            raw.add(place("want-" + index, name,
                    "제주특별자치도 제주시 애월읍", "A01"));
            preferences.append("{\"place_name\":\"")
                    .append(name)
                    .append("\",\"preference_type\":\"WANT\"}");
        }
        preferences.append(']');

        List<CourseCandidateShortlistService.ShortlistedPlace> result = service.select(
                request("[{\"code\":\"EAST\"}]", preferences.toString()), raw);

        assertThat(result).hasSize(16);
        assertThat(result).allMatch(candidate ->
                candidate.preferenceType() == PreferenceType.WANT);
    }

    @Test
    void removesDuplicateContentIdsBeforeShortlisting() throws Exception {
        TourPlaceDto first = place(
                "duplicate", "첫 장소", "제주특별자치도 제주시 구좌읍", "A01");
        TourPlaceDto duplicate = place(
                "duplicate", "중복 장소", "제주특별자치도 제주시 구좌읍", "A02");

        List<CourseCandidateShortlistService.ShortlistedPlace> result = service.select(
                request("[]", "[]"), List.of(first, duplicate));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).place().getTitle()).isEqualTo("첫 장소");
    }

    private CourseRequestDto request(String regions, String preferences) throws Exception {
        return objectMapper.readValue("""
                {
                  "start_date":"2026-08-28",
                  "end_date":"2026-08-29",
                  "people":2,
                  "budget_total":400000,
                  "transport":"RENTAL_CAR",
                  "course_regions":%s,
                  "course_styles":[{"code":"NATURE","weight":1}],
                  "course_place_preferences":%s
                }
                """.formatted(regions, preferences), CourseRequestDto.class);
    }

    @Test
    void insufficientSelectedRegionNeverFallsBackToWholeIsland() throws Exception {
        var result = service.select(request("[{\"code\":\"EAST\"}]", "[]"), List.of(
                place("west", "서부", "제주시 한림읍", "A01"),
                objectMapper.readValue("{\"contentid\":\"missing\",\"addr1\":\"제주시 구좌읍\"}", TourPlaceDto.class),
                objectMapper.readValue("{\"contentid\":\"outside\",\"addr1\":\"제주시 구좌읍\",\"mapy\":37.5,\"mapx\":127}", TourPlaceDto.class)));
        assertThat(result).isEmpty();
    }

    @Test
    void nearestCohortIsDeterministicAndUsesRequiredPlaceAnchor() throws Exception {
        List<TourPlaceDto> raw = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            raw.add(objectMapper.readValue("""
                    {"contentid":"p%02d","title":"장소%d","addr1":"제주시 구좌읍",
                     "mapy":33.4,"mapx":%s,"cat1":"A01"}
                    """.formatted(i, i, 126.5 + i * 0.01), TourPlaceDto.class));
        }
        var req = request("[{\"code\":\"EAST\"}]", """
                [{"place_name":"Kakao 필수 숙소 아닌 방문지","preference_type":"WANT",
                  "latitude":33.4,"longitude":126.5}]
                """);
        var first = service.select(req, raw).stream().map(c -> c.place().getContentId()).toList();
        java.util.Collections.reverse(raw);
        var second = service.select(req, raw).stream().map(c -> c.place().getContentId()).toList();
        assertThat(first).isEqualTo(second).hasSize(15);
        assertThat(first).containsExactlyElementsOf(java.util.stream.IntStream.range(0, 15)
                .mapToObj(i -> "p%02d".formatted(i)).toList());
        // Without WANT, the medoid and stable identity tie-break are also input-order independent.
        var noWant = request("[{\"code\":\"EAST\"}]", "[]");
        var centered = service.select(noWant, raw).stream().map(c -> c.place().getContentId()).toList();
        java.util.Collections.reverse(raw);
        assertThat(service.select(noWant, raw).stream().map(c -> c.place().getContentId()).toList())
                .isEqualTo(centered);
    }

    @Test
    void requiredPlaceWithMissingCoordinatesIsNotSilentlyDiscarded() throws Exception {
        var required = objectMapper.readValue("{\"contentid\":\"want\",\"title\":\"필수\"}", TourPlaceDto.class);
        assertThat(service.select(request("[{\"code\":\"EAST\"}]",
                "[{\"place_name\":\"필수\",\"preference_type\":\"WANT\"}]"), List.of(required)))
                .singleElement().satisfies(c -> assertThat(c.preferenceType()).isEqualTo(PreferenceType.WANT));
    }

    private TourPlaceDto place(
            String contentId,
            String title,
            String address,
            String category
    ) throws Exception {
        return at(contentId, title, address, category, 126.5);
    }

    @Test
    void emptyRegionsUsesWantAnchorAndIsInputOrderIndependent() throws Exception {
        List<TourPlaceDto> raw = new ArrayList<>();
        for (int i = 0; i < 16; i++) raw.add(at("near" + i, "근처" + i, "제주시", "A02", 126.5 + i * 0.001));
        raw.add(at("far", "먼 자연", "서귀포시", "A01", 126.95));
        var request = request("[]", """
                [{"place_name":"필수","preference_type":"WANT","latitude":33.4,"longitude":126.5}]
                """);
        var first = service.select(request, raw).stream().map(c -> c.place().getContentId()).toList();
        java.util.Collections.reverse(raw);
        assertThat(service.select(request, raw).stream().map(c -> c.place().getContentId()).toList())
                .isEqualTo(first).hasSize(15).doesNotContain("far");
        var noWant = request("[]", "[]");
        var centered = service.select(noWant, raw).stream().map(c -> c.place().getContentId()).toList();
        java.util.Collections.reverse(raw);
        assertThat(service.select(noWant, raw).stream().map(c -> c.place().getContentId()).toList())
                .isEqualTo(centered).doesNotContain("far");
    }

    @Test
    void similarDisplayNamesAreVarietyFilteredWithoutChangingSourceObjectsOrWants() throws Exception {
        var first = place("source1", "열안지오름(봉개동)", "제주시 봉개동", "A01");
        var second = at("source2", "열안지오름(오라동)", "제주시 오라동", "A01", 126.6);
        assertThat(service.select(request("[]", "[]"), List.of(first, second))).hasSize(1);
        assertThat(first.getContentId()).isEqualTo("source1");
        assertThat(second.getContentId()).isEqualTo("source2");
        assertThat(service.select(request("[]", """
                [{"place_name":"열안지오름(봉개동)","preference_type":"WANT"},
                 {"place_name":"열안지오름(오라동)","preference_type":"WANT"}]
                """), List.of(first, second))).hasSize(2);
    }

    private TourPlaceDto at(
            String contentId,
            String title,
            String address,
            String category,
            double longitude
    ) throws Exception {
        return objectMapper.readValue("""
                {"contentid":"%s","title":"%s","addr1":"%s",
                 "mapy":33.4,"mapx":%s,"cat1":"%s"}
                """.formatted(contentId, title, address, longitude, category), TourPlaceDto.class);
    }
}
