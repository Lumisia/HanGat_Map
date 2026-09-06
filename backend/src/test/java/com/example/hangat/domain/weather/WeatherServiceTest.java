package com.example.hangat.domain.weather;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.common.model.BaseResponseStatus;
import com.example.hangat.domain.weather.model.DailyWeather;
import com.example.hangat.domain.weather.model.MidLandItem;
import com.example.hangat.domain.weather.model.MidTaItem;
import com.example.hangat.domain.weather.model.ShortTermItem;
import com.example.hangat.domain.weather.repository.WeatherForecastRepository;
import com.example.hangat.map.model.entity.Region;
import com.example.hangat.map.repository.RegionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 라이브 병합 로직 검증 - WeatherClient는 목으로 대체(실호출 없음).
 * 리포지토리 목은 기본값(빈 결과)이라 DB 미적재 상태 = 라이브 폴백 경로를 그대로 검증한다.
 * DB 우선 경로는 {@link WeatherServiceStoreReadTest}.
 */
class WeatherServiceTest {

    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    private final LocalDate today = LocalDate.now(KmaIssueTimes.KST);   // 서비스와 같은 시계(KST)
    private final WeatherClient client = mock(WeatherClient.class);
    private final WeatherForecastRepository forecastRepository = mock(WeatherForecastRepository.class);
    private final RegionRepository regionRepository = mock(RegionRepository.class);
    private final WeatherService service = new WeatherService(client, forecastRepository, regionRepository);

    /** 기준 권역(북부)은 존재하되 저장 예보는 없는 상태 = 라이브 폴백 경로. 모르는 권역이면 빈 목록이 되어 병합 검증이 안 된다 */
    @BeforeEach
    void mainRegionExistsWithoutStoredForecast() {
        when(regionRepository.findByCode("NORTH")).thenReturn(Optional.of(
                Region.builder().code("NORTH").name("북부").displayOrder((byte) 1).build()));
    }

    /** D+0~3 각 날짜에 TMN/TMX/TMP/SKY/PTY/POP 행을 깔아주는 헬퍼 */
    private List<ShortTermItem> fakeShortTerm() {
        List<ShortTermItem> items = new ArrayList<>();
        for (int d = 0; d <= 3; d++) {
            String date = today.plusDays(d).format(YMD);
            if (d < 3) {
                // D+0~2: TMN/TMX 제공
                items.add(new ShortTermItem("TMN", date, "0600", String.valueOf(20 + d)));
                items.add(new ShortTermItem("TMX", date, "1500", String.valueOf(30 + d)));
            }
            // D+3은 TMN/TMX 없이 TMP만 - 폴백 검증용
            items.add(new ShortTermItem("TMP", date, "0900", String.valueOf(22 + d)));
            items.add(new ShortTermItem("TMP", date, "1500", String.valueOf(28 + d)));
            items.add(new ShortTermItem("SKY", date, "1200", "3"));
            items.add(new ShortTermItem("PTY", date, "1200", "0"));
            items.add(new ShortTermItem("POP", date, "0900", "10"));
            items.add(new ShortTermItem("POP", date, "1500", String.valueOf(40 + d)));
        }
        return items;
    }

    private MidTaItem fakeMidTa() {
        return new MidTaItem(null, null, 24, 31, 25, 32, 26, 33, 27, 34);
    }

    private MidLandItem fakeMidLand() {
        // wf3~wf7: null, 구름많음, 흐림, 맑음, 흐림 / rnSt3~rnSt7: null, 30, 40, 60, 70
        return new MidLandItem(null, "구름많음", "흐림", "맑음", "흐림", null, 30, 40, 60, 70,
                null, null, null, null, null, null, null, null);
    }

    @Test
    void 칠일을_날짜순으로_반환한다() {
        when(client.fetchShortTerm(eq(today.format(YMD)), anyString())).thenReturn(fakeShortTerm());
        when(client.fetchMidTemperature(anyString())).thenReturn(fakeMidTa());
        when(client.fetchMidLand(anyString())).thenReturn(fakeMidLand());

        List<DailyWeather> week = service.getWeeklyForecast();

        assertThat(week).hasSize(7);
        for (int i = 0; i < 7; i++) {
            assertThat(week.get(i).date()).isEqualTo(today.plusDays(i));
        }
    }

    @Test
    void 단기구간은_TMN_TMX와_최대_POP을_쓴다() {
        when(client.fetchShortTerm(eq(today.format(YMD)), anyString())).thenReturn(fakeShortTerm());
        when(client.fetchMidTemperature(anyString())).thenReturn(fakeMidTa());
        when(client.fetchMidLand(anyString())).thenReturn(fakeMidLand());

        DailyWeather day0 = service.getWeeklyForecast().get(0);

        assertThat(day0.minTemp()).isEqualTo(20);
        assertThat(day0.maxTemp()).isEqualTo(30);
        assertThat(day0.sky()).isEqualTo("구름많음");   // SKY=3, PTY=0
        assertThat(day0.rainProb()).isEqualTo(40);      // POP 10 vs 40 중 최대
    }

    @Test
    void TMN_TMX가_없으면_시간별_TMP의_최소최대로_폴백한다() {
        when(client.fetchShortTerm(eq(today.format(YMD)), anyString())).thenReturn(fakeShortTerm());
        when(client.fetchMidTemperature(anyString())).thenReturn(fakeMidTa());
        when(client.fetchMidLand(anyString())).thenReturn(fakeMidLand());

        DailyWeather day3 = service.getWeeklyForecast().get(3);

        assertThat(day3.minTemp()).isEqualTo(25);   // TMP 25/31 중 최소
        assertThat(day3.maxTemp()).isEqualTo(31);
    }

    @Test
    void 중기구간은_발표일_기준_인덱스로_필드를_고른다() {
        when(client.fetchShortTerm(eq(today.format(YMD)), anyString())).thenReturn(fakeShortTerm());
        when(client.fetchMidTemperature(eq(today.format(YMD) + "0600"))).thenReturn(fakeMidTa());
        when(client.fetchMidLand(eq(today.format(YMD) + "0600"))).thenReturn(fakeMidLand());

        List<DailyWeather> week = service.getWeeklyForecast();

        assertThat(week.get(4).minTemp()).isEqualTo(24);        // taMin4
        assertThat(week.get(4).sky()).isEqualTo("구름많음");     // wf4Am
        assertThat(week.get(6).maxTemp()).isEqualTo(33);        // taMax6
        assertThat(week.get(6).rainProb()).isEqualTo(60);       // rnSt6Am
    }

    @Test
    void 오늘_발표가_없으면_어제_발표분으로_폴백하고_인덱스가_하루_밀린다() {
        LocalDate yesterday = today.minusDays(1);
        when(client.fetchShortTerm(eq(today.format(YMD)), anyString()))
                .thenThrow(new BaseException(BaseResponseStatus.EXTERNAL_API_ERROR));
        when(client.fetchShortTerm(eq(yesterday.format(YMD)), eq("2300"))).thenReturn(fakeShortTerm());
        when(client.fetchMidTemperature(eq(today.format(YMD) + "0600")))
                .thenThrow(new BaseException(BaseResponseStatus.EXTERNAL_API_ERROR));
        when(client.fetchMidTemperature(eq(yesterday.format(YMD) + "1800"))).thenReturn(fakeMidTa());
        when(client.fetchMidLand(eq(yesterday.format(YMD) + "1800"))).thenReturn(fakeMidLand());

        List<DailyWeather> week = service.getWeeklyForecast();

        // 어제 발표 기준으로 오늘+4 = 발표일+5 → taMin5/wf5Am이 와야 한다
        assertThat(week.get(4).minTemp()).isEqualTo(25);
        assertThat(week.get(4).sky()).isEqualTo("흐림");
    }
}
