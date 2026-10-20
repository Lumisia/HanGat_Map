package com.example.hangat.course.ai;

import com.example.hangat.course.ai.CourseAiInputDto.CandidateFactDto;
import com.example.hangat.course.ai.CourseAiInputDto.CongestionFactDto;
import com.example.hangat.course.ai.CourseAiInputDto.RequiredCandidateConstraintDto;
import com.example.hangat.course.ai.CourseAiInputDto.WeatherAiFactDto;
import com.example.hangat.course.ai.CourseAiInputDto.WeatherAiFactSetDto;
import com.example.hangat.course.model.Transport;
import com.example.hangat.course.service.RainyDayRule;
import com.example.hangat.map.model.enums.CongestionLevel;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LLM이 실패해도 우천 동작은 배치 코스와 같아야 한다 - 후보를 고르는 순서(스타일·혼잡)는 그대로 두고 실내 후보는
 * 비 예보일로, 실외 후보는 맑은 날로 보낸다. 비 예보일이 없으면 기존 순서 그대로, WANT·고정 일정은 규칙보다 우선.
 */
class DeterministicCourseFallbackRainTest {

    private static final LocalDate RAINY = LocalDate.of(2026, 9, 20);
    private static final LocalDate SUNNY = LocalDate.of(2026, 9, 21);
    private static final LocalDate DAY3 = LocalDate.of(2026, 9, 22);
    private static final String EAST = "db-weather-EAST";

    private final DeterministicCourseFallback fallback = new DeterministicCourseFallback();

    /** 후보 여섯 곳 - id 순서가 곧 기본 순서(스타일·혼잡 동률). 실내 셋, 실외 셋이 번갈아 온다. */
    private static final List<CandidateFactDto> CANDIDATES = List.of(
            candidate("a", "성산일출봉", EAST),
            candidate("b", "제주민속자연사박물관", EAST),
            candidate("c", "협재해수욕장", EAST),
            candidate("d", "빛의 벙커 전시관", EAST),
            candidate("e", "천지연폭포", EAST),
            candidate("f", "산방산탄산온천", EAST));

    @Test
    void 비_예보일에는_실내_후보를_먼저_담고_사유에_비_예보를_적는다() {
        CourseAiResultDto result = fallback.generate(input(CANDIDATES, List.of(), weather(Map.of(RAINY, 80, SUNNY, 10))));

        Map<LocalDate, List<String>> byDate = names(result);
        // 비 오는 날: 실내 셋(b, d, f)이 먼저 - 하루 정원 3이라 전부 실내
        assertThat(byDate.get(RAINY)).containsExactly("b", "d", "f");
        assertThat(byDate.get(SUNNY)).containsExactly("a", "c", "e");
        result.days().stream().filter(day -> day.date().equals(RAINY)).flatMap(day -> day.items().stream())
                .forEach(item -> assertThat(item.recommendationReason()).isEqualTo(RainyDayRule.INDOOR_REASON));
        result.days().stream().filter(day -> day.date().equals(SUNNY)).flatMap(day -> day.items().stream())
                .forEach(item -> assertThat(item.recommendationReason()).isNotEqualTo(RainyDayRule.INDOOR_REASON));
    }

    @Test
    void 비_예보일이_둘째_날이어도_맑은_첫날이_실내_후보를_먼저_써_버리지_않는다() {
        // 실내 후보는 비 예보일로, 실외 후보는 맑은 날로 - 둘째 날이 비여도 맑은 첫날이 실내 후보를 써 버리지 않는다
        CourseAiResultDto result = fallback.generate(input(CANDIDATES, List.of(), weather(Map.of(RAINY, 10, SUNNY, 80))));

        Map<LocalDate, List<String>> byDate = names(result);
        assertThat(byDate.get(SUNNY)).containsExactly("b", "d", "f");   // 둘째 날이 비 - 실내 셋
        assertThat(byDate.get(RAINY)).containsExactly("a", "c", "e");   // 첫날은 맑음 - 실외부터
    }

    @Test
    void 맑은_날만_있으면_날씨가_없을_때와_결과가_같다() {
        CourseAiResultDto withWeather = fallback.generate(input(CANDIDATES, List.of(), weather(Map.of(RAINY, 10, SUNNY, 20))));
        CourseAiResultDto withoutWeather = fallback.generate(input(CANDIDATES, List.of(), List.of()));

        assertThat(withWeather).isEqualTo(withoutWeather);
        // 기존 순서: 날짜를 번갈아 채우므로 a·c·e / b·d·f
        assertThat(names(withWeather).get(RAINY)).containsExactly("a", "c", "e");
    }

    @Test
    void 임계값_바로_아래는_비_예보일이_아니다() {
        CourseAiResultDto result = fallback.generate(input(CANDIDATES, List.of(),
                weather(Map.of(RAINY, RainyDayRule.PROB_FROM - 1, SUNNY, 0))));

        assertThat(names(result).get(RAINY)).containsExactly("a", "c", "e");
    }

    @Test
    void 고정_일정과_WANT는_우천_규칙보다_우선한다() {
        // 실외인 성산일출봉을 비 오는 날 10시에 고정 - 규칙이 이를 밀어내면 안 된다
        List<RequiredCandidateConstraintDto> required = List.of(
                new RequiredCandidateConstraintDto("a", RAINY, LocalTime.of(10, 0)));

        CourseAiResultDto result = fallback.generate(input(CANDIDATES, required, weather(Map.of(RAINY, 90, SUNNY, 10))));

        List<String> rainyDay = names(result).get(RAINY);
        assertThat(rainyDay.get(0)).isEqualTo("a");
        assertThat(rainyDay.subList(1, rainyDay.size())).containsExactly("b", "d");   // 남은 두 자리는 실내 우선
    }

    @Test
    void 스타일_후보는_그대로_먼저_고르고_실내면_비_예보일로_보낸다() {
        // 카페 취향 - 후보를 고르는 순서는 우천 규칙과 무관하다(카페가 먼저). 고른 카페가 실내라 비 오는 둘째 날로 간다
        List<CandidateFactDto> candidates = List.of(
                new CandidateFactDto("cafe", "월정리 카페", "EAST", "CAFE", List.of("CAFE"), List.of(), EAST, null),
                new CandidateFactDto("oreum", "다랑쉬오름", "EAST", "TOURIST", List.of(), List.of(), EAST, null));

        CourseAiResultDto result = fallback.generate(
                input(candidates, List.of(), weather(Map.of(RAINY, 10, SUNNY, 80)), List.of("CAFE"), SUNNY));

        assertThat(names(result).get(SUNNY)).containsExactly("cafe");    // 둘째 날이 비 - 실내인 카페
        assertThat(names(result).get(RAINY)).containsExactly("oreum");   // 첫날은 맑음 - 실외
        assertThat(result.days().stream().filter(day -> day.date().equals(SUNNY)).findFirst().orElseThrow()
                .items().get(0).recommendationReason()).isEqualTo(RainyDayRule.INDOOR_REASON);
    }

    @Test
    void 비_예보일이라도_혼잡한_실내가_한산한_후보보다_먼저_자리를_잡지_않는다() {
        // 하루 세 자리에 후보 넷 - 비가 와도 후보 순서는 혼잡 낮은 순 그대로라 붐비는 박물관이 밀려난다
        List<CandidateFactDto> candidates = List.of(
                congested("b", "협재해수욕장", CongestionLevel.QUIET),
                congested("g", "빛의 벙커 전시관", CongestionLevel.QUIET),
                congested("m", "제주민속자연사박물관", CongestionLevel.CROWDED),
                congested("o", "다랑쉬오름", CongestionLevel.QUIET));

        CourseAiResultDto result = fallback.generate(
                input(candidates, List.of(), weather(Map.of(RAINY, 90)), List.of(), RAINY));

        assertThat(names(result).get(RAINY)).containsExactly("b", "g", "o").doesNotContain("m");
    }

    @Test
    void 비_예보일이_다_차면_실내_후보도_맑은_날로_간다() {
        List<CandidateFactDto> candidates = List.of(
                candidate("a", "성산일출봉", EAST), candidate("b", "제주민속자연사박물관", EAST),
                candidate("c", "협재해수욕장", EAST), candidate("d", "빛의 벙커 전시관", EAST),
                candidate("e", "천지연폭포", EAST), candidate("f", "산방산탄산온천", EAST),
                candidate("g", "용머리해안", EAST), candidate("h", "제주도립미술관", EAST),
                candidate("i", "쇠소깍", EAST));

        CourseAiResultDto result = fallback.generate(
                input(candidates, List.of(), weather(Map.of(RAINY, 90, SUNNY, 10, DAY3, 10)), List.of(), DAY3));

        Map<LocalDate, List<String>> byDate = names(result);
        assertThat(byDate.get(RAINY)).containsExactly("b", "d", "f");
        assertThat(byDate.get(SUNNY)).containsExactly("a", "e", "h");   // 비 오는 날이 다 찬 뒤엔 h(미술관)도 맑은 날로 간다
        assertThat(byDate.get(DAY3)).containsExactly("c", "g", "i");
    }

    @Test
    void 실내_후보만_있어도_비_예보일이_아닌_날을_비워_두지_않는다() {
        // 실내 셋뿐이고 둘째 날만 비 - 셋 다 비 오는 날로 몰리면 첫날이 비어 코스가 성립하지 않는다
        List<CandidateFactDto> indoorOnly = List.of(
                candidate("b", "제주민속자연사박물관", EAST),
                candidate("d", "빛의 벙커 전시관", EAST),
                candidate("f", "산방산탄산온천", EAST));

        CourseAiResultDto result = fallback.generate(input(indoorOnly, List.of(), weather(Map.of(RAINY, 10, SUNNY, 80))));

        Map<LocalDate, List<String>> byDate = names(result);
        assertThat(byDate.get(SUNNY)).containsExactly("b", "d");   // 둘째 날이 비 - 실내 둘
        assertThat(byDate.get(RAINY)).containsExactly("f");        // 마지막 후보는 빈 첫날로
    }

    @Test
    void 날씨_세트가_없는_후보는_비_예보일로_보지_않는다() {
        List<CandidateFactDto> noWeather = List.of(
                candidate("a", "성산일출봉", null),
                candidate("b", "제주민속자연사박물관", null),
                candidate("c", "협재해수욕장", null),
                candidate("d", "빛의 벙커 전시관", null));

        CourseAiResultDto result = fallback.generate(input(noWeather, List.of(), weather(Map.of(RAINY, 95, SUNNY, 95))));

        // 세트를 참조하지 않으니 기존 순서 그대로 - 모르는 날씨로 실내를 우선하지 않는다
        assertThat(names(result).get(RAINY)).containsExactly("a", "c");
        assertThat(names(result).get(SUNNY)).containsExactly("b", "d");
    }

    private static CandidateFactDto candidate(String id, String name, String weatherFactSetId) {
        return new CandidateFactDto(id, name, "EAST", "TOURIST", List.of(), List.of(), weatherFactSetId, null);
    }

    private static List<WeatherAiFactSetDto> weather(Map<LocalDate, Integer> probabilityByDate) {
        return List.of(new WeatherAiFactSetDto(EAST, probabilityByDate.entrySet().stream()
                .map(entry -> new WeatherAiFactDto(entry.getKey(), null, null, entry.getValue(), "0", "1", null))
                .toList()));
    }

    private static CandidateFactDto congested(String id, String name, CongestionLevel level) {
        return new CandidateFactDto(id, name, "EAST", "TOURIST", List.of(),
                List.of(new CongestionFactDto(RAINY, BigDecimal.valueOf(50), level)), EAST, null);
    }

    private static CourseAiInputDto input(List<CandidateFactDto> candidates,
                                          List<RequiredCandidateConstraintDto> required,
                                          List<WeatherAiFactSetDto> weatherFactSets) {
        return input(candidates, required, weatherFactSets, List.of(), SUNNY);
    }

    private static CourseAiInputDto input(List<CandidateFactDto> candidates,
                                          List<RequiredCandidateConstraintDto> required,
                                          List<WeatherAiFactSetDto> weatherFactSets,
                                          List<String> styles, LocalDate endDate) {
        return new CourseAiInputDto("2.0",
                new CourseAiInputDto.TripConstraintDto(RAINY, endDate, Transport.RENTAL_CAR),
                new CourseAiInputDto.SoftPreferencesDto(List.of("EAST"), styles),
                new CourseAiInputDto.HardConstraintsDto(required), null,
                candidates, weatherFactSets, List.of(), null);
    }

    /** 날짜별 candidateId 목록(시작 시간 순). */
    private static Map<LocalDate, List<String>> names(CourseAiResultDto result) {
        return result.days().stream().collect(java.util.stream.Collectors.toMap(
                CourseAiResultDto.DayDto::date,
                day -> day.items().stream().map(CourseAiResultDto.ItemDto::candidateId).toList()));
    }
}
