package com.example.hangat.domain.weather;

import com.example.hangat.domain.weather.WeatherDailySummarizer.DailySummary;
import com.example.hangat.domain.weather.model.MidLandItem;
import com.example.hangat.domain.weather.model.MidTaItem;
import com.example.hangat.domain.weather.model.ShortTermItem;
import com.example.hangat.domain.weather.model.enums.PrecipitationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 하루 요약 규칙 - 특히 중기 오전·오후 병합의 결측 조합. 결측이 흔한 공공 API라 null 경로를 전부 밟는다. */
class WeatherDailySummarizerTest {

    private static final LocalDate ISSUE = LocalDate.of(2026, 9, 5);

    private static MidTaItem ta() {
        return new MidTaItem(null, null, 21, 27, 22, 28, 23, 29, 24, 30);
    }

    @Test
    @DisplayName("오전·오후 강수확률은 큰 쪽, 하늘은 비·눈 있는 쪽 - 오후에만 비가 있어도 하루는 비")
    void mergesAmAndPm() {
        MidLandItem land = new MidLandItem(null, "맑음", "구름많음", "흐림", "맑음",
                null, 10, 20, 30, 40,
                "흐리고 비", "구름많음", "흐리고 눈", "맑음",
                60, 20, 70, 40);

        DailySummary day4 = WeatherDailySummarizer.fromMid(ISSUE.plusDays(4), ISSUE, ta(), land);
        assertThat(day4.sky()).isEqualTo("흐리고 비");
        assertThat(day4.rainProb()).isEqualTo(60);
        assertThat(day4.precipitationType()).isEqualTo(PrecipitationType.RAIN);
        assertThat(day4.tempMin()).isEqualTo(21);

        DailySummary day6 = WeatherDailySummarizer.fromMid(ISSUE.plusDays(6), ISSUE, ta(), land);
        assertThat(day6.sky()).isEqualTo("흐리고 눈");
        assertThat(day6.precipitationType()).isEqualTo(PrecipitationType.SNOW);
        assertThat(day6.rainProb()).isEqualTo(70);

        DailySummary day5 = WeatherDailySummarizer.fromMid(ISSUE.plusDays(5), ISSUE, ta(), land);
        assertThat(day5.sky()).isEqualTo("구름많음");   // 둘 다 강수 없음 → 오전
        assertThat(day5.rainProb()).isEqualTo(20);
    }

    @Test
    @DisplayName("강수확률·하늘이 오전·오후 모두 없으면 null - 언박싱 NPE 없이, 0으로 메우지도 않는다")
    void bothMissingStayNull() {
        MidLandItem land = new MidLandItem(null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null,
                null, null, null, null);

        DailySummary day4 = WeatherDailySummarizer.fromMid(ISSUE.plusDays(4), ISSUE, ta(), land);

        assertThat(day4.rainProb()).isNull();
        assertThat(day4.sky()).isNull();
        assertThat(day4.precipitationType()).isNull();
        assertThat(day4.isEmpty()).isFalse();   // 기온은 있다
    }

    @Test
    @DisplayName("한쪽만 있으면 그 값 - 오전 결측이면 오후, 오후 결측이면 오전")
    void oneSideMissingUsesTheOther() {
        MidLandItem land = new MidLandItem(null, null, "맑음", null, null,
                null, null, 30, null, null,
                "흐림", null, null, null,
                50, null, null, null);

        DailySummary day4 = WeatherDailySummarizer.fromMid(ISSUE.plusDays(4), ISSUE, ta(), land);
        assertThat(day4.sky()).isEqualTo("흐림");
        assertThat(day4.rainProb()).isEqualTo(50);

        DailySummary day5 = WeatherDailySummarizer.fromMid(ISSUE.plusDays(5), ISSUE, ta(), land);
        assertThat(day5.sky()).isEqualTo("맑음");
        assertThat(day5.rainProb()).isEqualTo(30);
    }

    @Test
    @DisplayName("발표일 기준 +4~+7 밖의 날짜는 빈 요약 - 행을 만들지 않는 신호")
    void outOfRangeIsEmpty() {
        MidLandItem land = new MidLandItem(null, "맑음", "맑음", "맑음", "맑음",
                null, 10, 10, 10, 10, null, null, null, null, null, null, null, null);

        assertThat(WeatherDailySummarizer.fromMid(ISSUE.plusDays(3), ISSUE, ta(), land).isEmpty()).isTrue();
        assertThat(WeatherDailySummarizer.fromMid(ISSUE.plusDays(8), ISSUE, ta(), land).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("단기: TMN/TMX 우선, 강수확률은 하루 최대, 정오 PTY가 있으면 하늘은 비·눈")
    void shortTermSummary() {
        List<ShortTermItem> items = List.of(
                new ShortTermItem("TMN", "20260906", "0600", "21.0"),
                new ShortTermItem("TMX", "20260906", "1500", "29.0"),
                new ShortTermItem("TMP", "20260906", "0900", "24"),
                new ShortTermItem("POP", "20260906", "0900", "20"),
                new ShortTermItem("POP", "20260906", "1500", "80"),
                new ShortTermItem("SKY", "20260906", "1200", "1"),
                new ShortTermItem("PTY", "20260906", "1200", "2"));

        DailySummary day = WeatherDailySummarizer.fromShortTerm(LocalDate.of(2026, 9, 6), items);

        assertThat(day.tempMin()).isEqualTo(21);
        assertThat(day.tempMax()).isEqualTo(29);
        assertThat(day.rainProb()).isEqualTo(80);
        assertThat(day.sky()).isEqualTo("비");
        assertThat(day.precipitationType()).isEqualTo(PrecipitationType.RAIN_SNOW);   // 코드 2 = 비/눈, 텍스트보다 정확
        assertThat(WeatherDailySummarizer.fromShortTerm(LocalDate.of(2026, 9, 7), items).isEmpty()).isTrue();
    }
}
