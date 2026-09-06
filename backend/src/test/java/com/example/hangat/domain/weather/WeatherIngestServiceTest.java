package com.example.hangat.domain.weather;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.common.model.BaseResponseStatus;
import com.example.hangat.domain.weather.WeatherIngestService.WeatherIngestResult;
import com.example.hangat.domain.weather.model.MidLandItem;
import com.example.hangat.domain.weather.model.MidTaItem;
import com.example.hangat.domain.weather.model.ShortTermItem;
import com.example.hangat.domain.weather.model.entity.WeatherForecast;
import com.example.hangat.domain.weather.model.enums.PrecipitationType;
import com.example.hangat.domain.weather.model.enums.WeatherGranularity;
import com.example.hangat.domain.weather.repository.WeatherForecastRepository;
import com.example.hangat.map.model.entity.Region;
import com.example.hangat.map.repository.DataSourceRepository;
import com.example.hangat.map.repository.RegionRepository;
import com.example.hangat.map.service.PlaceNameNormalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * 날씨 적재 - 권역별 단기 + 전역 중기, 발표 버전 append, 실패 격리.
 * 기상청은 모킹한다(라이브 호출은 {@code WeatherClientManualTest}).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class WeatherIngestServiceTest {

    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 10);
    /** 06:30 → 단기 05:00 발표분, 중기 06:00 발표분 */
    private static final LocalDateTime MORNING = TODAY.atTime(6, 30);
    private static final LocalDateTime SHORT_BASE_UTC = LocalDateTime.of(2026, 9, 9, 20, 0);   // KST 9/10 05:00
    private static final LocalDateTime MID_BASE_UTC = LocalDateTime.of(2026, 9, 9, 21, 0);     // KST 9/10 06:00

    @Autowired WeatherIngestService service;
    @Autowired WeatherForecastRepository repository;
    @Autowired RegionRepository regionRepository;
    @Autowired DataSourceRepository dataSourceRepository;
    @MockitoBean WeatherClient client;

    private Region north;
    private Region east;

    @BeforeEach
    void setUp() {
        north = regionRepository.findByCode("NORTH").orElseThrow();
        east = regionRepository.findByCode("EAST").orElseThrow();
        given(client.fetchShortTerm(eq("20260910"), eq("0500"), anyInt(), anyInt()))
                .willAnswer(inv -> shortItems(inv.getArgument(3), TODAY, 4));
        given(client.fetchMidTemperature("202609100600")).willReturn(midTa());
        given(client.fetchMidLand("202609100600")).willReturn(midLand());
    }

    @Test
    @DisplayName("권역 4곳 × 단기 4일 + 중기 4일 = 32행. 단기는 권역 격자마다 다르고 중기는 네 권역이 같다")
    void storesShortPerRegionAndMidForAll() {
        WeatherIngestResult result = service.ingest(MORNING);

        assertThat(result.regions()).isEqualTo(4);
        assertThat(result.shortRows()).isEqualTo(16);
        assertThat(result.midRows()).isEqualTo(16);
        assertThat(result.inserted()).isEqualTo(32);
        assertThat(result.updated()).isZero();
        assertThat(result.shortFailures()).isZero();
        assertThat(result.midFailed()).isFalse();
        assertThat(result.shortIssuedAtKst()).isEqualTo("202609100500");
        assertThat(result.midIssuedAtKst()).isEqualTo("202609100600");
        assertThat(repository.count()).isEqualTo(32);

        // 단기: 북부(ny 38) 9/11 - 격자 오프셋 8, 날짜 오프셋 1
        WeatherForecast northDay1 = latest(north, TODAY.plusDays(1));
        assertThat(northDay1.getBaseAt()).isEqualTo(SHORT_BASE_UTC);
        assertThat(northDay1.getForecastAt()).isEqualTo(PlaceNameNormalizer.jejuDayToUtc(TODAY.plusDays(1)));
        assertThat(northDay1.getSource().getCode()).isEqualTo("KMA_SHORT");
        assertThat(northDay1.getTempMin()).isEqualByComparingTo(new BigDecimal("19"));
        assertThat(northDay1.getTempMax()).isEqualByComparingTo(new BigDecimal("29"));
        assertThat(northDay1.rainProbabilityPercent()).isEqualTo(60);
        assertThat(northDay1.getSkyCode()).isEqualTo("맑음");
        assertThat(northDay1.getPrecipitationType()).isEqualTo(PrecipitationType.NONE);
        assertThat(northDay1.getGranularity()).isEqualTo(WeatherGranularity.DAILY);
        assertThat(northDay1.getTemperature()).isNull();

        // 단기: 동부(ny 37)는 같은 날 값이 다르다 - 권역 격자를 따로 불렀다는 증거
        assertThat(latest(east, TODAY.plusDays(1)).getTempMin()).isEqualByComparingTo(new BigDecimal("18"));

        // 단기 둘째 날은 PTY 1(비) - 하늘 텍스트와 강수 형태가 코드에서 나온다
        assertThat(latest(north, TODAY.plusDays(2)).getSkyCode()).isEqualTo("비");
        assertThat(latest(north, TODAY.plusDays(2)).getPrecipitationType()).isEqualTo(PrecipitationType.RAIN);

        // 중기: 9/15(발표일 +5) - 제주도 단위 값이 네 권역에 같이, 출처 KMA_MID, 강수 형태는 텍스트에서
        WeatherForecast eastMid = latest(east, TODAY.plusDays(5));
        assertThat(eastMid.getBaseAt()).isEqualTo(MID_BASE_UTC);
        assertThat(eastMid.getSource().getCode()).isEqualTo("KMA_MID");
        assertThat(eastMid.getTempMin()).isEqualByComparingTo(new BigDecimal("22"));
        assertThat(eastMid.getTempMax()).isEqualByComparingTo(new BigDecimal("28"));
        assertThat(eastMid.getSkyCode()).isEqualTo("흐리고 비");
        assertThat(eastMid.getPrecipitationType()).isEqualTo(PrecipitationType.RAIN);
        assertThat(eastMid.rainProbabilityPercent()).isEqualTo(70);
        assertThat(repository.findByBaseAtAndGranularityOrderByRegionIdAscForecastAtAsc(MID_BASE_UTC, WeatherGranularity.DAILY)
                .stream().filter(f -> f.getForecastAt().equals(PlaceNameNormalizer.jejuDayToUtc(TODAY.plusDays(5)))))
                .hasSize(4);
        assertThat(latest(north, TODAY.plusDays(7)).getPrecipitationType()).isEqualTo(PrecipitationType.RAIN_SNOW);

        // 중기 9/16(발표일 +6): 오전 '맑음' 10% / 오후 '흐리고 비' 60% → 하루는 비, 60% - 오전만 보면 '맑음'이 된다
        WeatherForecast pmRain = latest(north, TODAY.plusDays(6));
        assertThat(pmRain.getSkyCode()).isEqualTo("흐리고 비");
        assertThat(pmRain.getPrecipitationType()).isEqualTo(PrecipitationType.RAIN);
        assertThat(pmRain.rainProbabilityPercent()).isEqualTo(60);
    }

    @Test
    @DisplayName("같은 발표분을 다시 돌리면 값만 갱신한다 - 행이 늘지 않고 id가 그대로라 코스 스냅숏이 산다")
    void rerunSameIssueUpdatesInPlace() {
        service.ingest(MORNING);
        Long idBefore = latest(north, TODAY.plusDays(1)).getId();

        WeatherIngestResult second = service.ingest(MORNING);

        assertThat(second.updated()).isEqualTo(32);
        assertThat(second.inserted()).isZero();
        assertThat(repository.count()).isEqualTo(32);
        assertThat(latest(north, TODAY.plusDays(1)).getId()).isEqualTo(idBefore);
    }

    @Test
    @DisplayName("재실행에서 한 권역만 실패하면 그 권역의 기존 행은 그대로 남는다 - 부분 실패가 삭제로 번지지 않는다")
    void partialRerunKeepsPreviouslyStoredRegion() {
        service.ingest(MORNING);
        given(client.fetchShortTerm(eq("20260910"), eq("0500"), anyInt(), eq(37)))
                .willThrow(new BaseException(BaseResponseStatus.EXTERNAL_API_ERROR, "timeout"));

        WeatherIngestResult second = service.ingest(MORNING);

        assertThat(second.shortFailures()).isEqualTo(1);
        assertThat(second.updated()).isEqualTo(28);
        assertThat(repository.count()).isEqualTo(32);
        assertThat(latest(east, TODAY).getSource().getCode()).isEqualTo("KMA_SHORT");
    }

    @Test
    @DisplayName("범위 밖 값은 NULL - 깨진 값 하나가 행을 막지도, 그럴듯한 값으로 남지도 않는다")
    void clampsOutOfRangeValuesToNull() {
        given(client.fetchShortTerm(eq("20260910"), eq("0500"), anyInt(), eq(38)))
                .willReturn(List.of(
                        new ShortTermItem("TMN", "20260910", "0600", "-99"),
                        new ShortTermItem("TMX", "20260910", "1500", "27"),
                        new ShortTermItem("POP", "20260910", "1200", "150"),
                        new ShortTermItem("SKY", "20260910", "1200", "1")));

        service.ingest(MORNING);

        WeatherForecast row = latest(north, TODAY);
        assertThat(row.getTempMin()).isNull();
        assertThat(row.getTempMax()).isEqualByComparingTo(new BigDecimal("27"));
        assertThat(row.rainProbabilityPercent()).isNull();
        assertThat(row.getSkyCode()).isEqualTo("맑음");
    }

    @Test
    @DisplayName("한 권역의 단기예보 실패는 그 권역만 빠지고 나머지 권역·중기는 저장된다")
    void isolatesRegionFailure() {
        given(client.fetchShortTerm(eq("20260910"), eq("0500"), anyInt(), eq(37)))
                .willThrow(new BaseException(BaseResponseStatus.EXTERNAL_API_ERROR, "timeout"));

        WeatherIngestResult result = service.ingest(MORNING);

        assertThat(result.shortFailures()).isEqualTo(1);
        assertThat(result.shortRows()).isEqualTo(12);
        assertThat(result.midRows()).isEqualTo(16);
        assertThat(repository.count()).isEqualTo(28);
        assertThat(repository.findFirstByRegionIdAndForecastAtAndGranularityOrderByBaseAtDesc(
                east.getId(), PlaceNameNormalizer.jejuDayToUtc(TODAY), WeatherGranularity.DAILY)).isEmpty();
        assertThat(latest(east, TODAY.plusDays(5)).getSource().getCode()).isEqualTo("KMA_MID");
    }

    @Test
    @DisplayName("중기예보 실패는 단기 저장을 막지 않고 결과에 midFailed로 남는다")
    void keepsShortWhenMidFails() {
        given(client.fetchMidTemperature(anyString()))
                .willThrow(new BaseException(BaseResponseStatus.EXTERNAL_API_ERROR, "03 NODATA"));

        WeatherIngestResult result = service.ingest(MORNING);

        assertThat(result.midFailed()).isTrue();
        assertThat(result.midRows()).isZero();
        assertThat(result.shortRows()).isEqualTo(16);
        assertThat(repository.count()).isEqualTo(16);
    }

    @Test
    @DisplayName("자료가 없는 날은 행을 만들지 않는다 - 0으로 채우지 않는다")
    void skipsDaysWithoutData() {
        given(client.fetchShortTerm(eq("20260910"), eq("0500"), anyInt(), anyInt()))
                .willAnswer(inv -> shortItems(inv.getArgument(3), TODAY, 2));

        WeatherIngestResult result = service.ingest(MORNING);

        assertThat(result.shortRows()).isEqualTo(8);
        assertThat(repository.findFirstByRegionIdAndForecastAtAndGranularityOrderByBaseAtDesc(
                north.getId(), PlaceNameNormalizer.jejuDayToUtc(TODAY.plusDays(3)), WeatherGranularity.DAILY)).isEmpty();
    }

    @Test
    @DisplayName("새벽 3시 30분: 단기 02시 발표분, 중기는 전날 18시 발표분 - 단기가 덮는 D+3은 중기에서 뺀다")
    void beforeDawnUsesYesterdayMid() {
        given(client.fetchShortTerm(eq("20260910"), eq("0200"), anyInt(), anyInt()))
                .willAnswer(inv -> shortItems(inv.getArgument(3), TODAY, 4));
        given(client.fetchMidTemperature("202609091800")).willReturn(midTa());
        given(client.fetchMidLand("202609091800")).willReturn(midLand());

        WeatherIngestResult result = service.ingest(TODAY.atTime(3, 30));

        assertThat(result.shortIssuedAtKst()).isEqualTo("202609100200");
        assertThat(result.midIssuedAtKst()).isEqualTo("202609091800");
        assertThat(result.shortRows()).isEqualTo(16);
        // 전날 발표 +4~+7 = 9/13~9/16 중 단기가 덮는 9/13은 제외 → 3일 × 4권역
        assertThat(result.midRows()).isEqualTo(12);
        assertThat(latest(north, TODAY.plusDays(3)).getSource().getCode()).isEqualTo("KMA_SHORT");
        assertThat(latest(north, TODAY.plusDays(4)).getSource().getCode()).isEqualTo("KMA_MID");
    }

    @Test
    @DisplayName("저녁 18시 중기 발표분은 +5일부터라 D+4가 빈다 - 같은 날 06시 발표분으로 D+4를 채우고, 재실행도 발표분별 upsert라 안전하다")
    void eveningMidIssueFillsDayFourFromMorningIssue() {
        // 18시 발표분: +4 필드 없음(2026-09 실측), +5~+7만
        given(client.fetchMidTemperature("202609101800"))
                .willReturn(new MidTaItem(null, null, null, null, 22, 28, 23, 29, 24, 30));
        given(client.fetchMidLand("202609101800"))
                .willReturn(new MidLandItem(null, null, "흐리고 비", "맑음", "흐리고 비/눈",
                        null, null, 70, 10, 40,
                        null, "맑음", "흐리고 비", "흐리고 비/눈",
                        null, 20, 60, 40));
        // 같은 날 06시 발표분에는 +4가 있다 (setUp의 midTa/midLand)

        WeatherIngestResult result = service.ingest(TODAY.atTime(20, 0));

        assertThat(result.midIssuedAtKst()).isEqualTo("202609101800");
        assertThat(result.midRows()).isEqualTo(16);
        WeatherForecast dayFour = latest(north, TODAY.plusDays(4));
        assertThat(dayFour.getBaseAt()).isEqualTo(MID_BASE_UTC);                            // 06:00 KST 발표분
        assertThat(dayFour.getTempMin()).isEqualByComparingTo(new BigDecimal("21"));
        assertThat(latest(north, TODAY.plusDays(5)).getBaseAt()).isEqualTo(LocalDateTime.of(2026, 9, 10, 9, 0));   // 18:00 KST
        assertThat(latest(north, TODAY.plusDays(5)).getTempMin()).isEqualByComparingTo(new BigDecimal("22"));

        WeatherIngestResult again = service.ingest(TODAY.atTime(20, 0));
        assertThat(again.inserted()).isZero();
        assertThat(again.updated()).isEqualTo(32);
        assertThat(repository.count()).isEqualTo(32);
    }

    @Test
    @DisplayName("D+4 보충용 06시 발표분 수집이 실패해도 나머지는 저장되고 D+4만 비어 있다")
    void dayFourFillFailureIsIsolated() {
        given(client.fetchMidTemperature("202609101800"))
                .willReturn(new MidTaItem(null, null, null, null, 22, 28, 23, 29, 24, 30));
        given(client.fetchMidLand("202609101800"))
                .willReturn(new MidLandItem(null, null, "흐리고 비", "맑음", "흐리고 비/눈",
                        null, null, 70, 10, 40, null, "맑음", "흐리고 비", "흐리고 비/눈", null, 20, 60, 40));
        given(client.fetchMidTemperature("202609100600"))
                .willThrow(new BaseException(BaseResponseStatus.EXTERNAL_API_ERROR, "down"));

        WeatherIngestResult result = service.ingest(TODAY.atTime(20, 0));

        assertThat(result.midFailed()).isFalse();
        assertThat(result.midRows()).isEqualTo(12);
        assertThat(repository.findFirstByRegionIdAndForecastAtAndGranularityOrderByBaseAtDesc(
                north.getId(), PlaceNameNormalizer.jejuDayToUtc(TODAY.plusDays(4)), WeatherGranularity.DAILY)).isEmpty();
        assertThat(latest(north, TODAY.plusDays(5)).getTempMin()).isEqualByComparingTo(new BigDecimal("22"));
    }

    @Test
    @DisplayName("받은 게 하나도 없으면 기존 버전을 지우지 않는다 - 실패가 데이터 손실로 번지지 않게")
    void doesNotDeleteExistingVersionWhenNothingFetched() {
        service.ingest(MORNING);
        given(client.fetchShortTerm(anyString(), anyString(), anyInt(), anyInt()))
                .willThrow(new BaseException(BaseResponseStatus.EXTERNAL_API_ERROR, "down"));
        given(client.fetchMidTemperature(anyString()))
                .willThrow(new BaseException(BaseResponseStatus.EXTERNAL_API_ERROR, "down"));

        WeatherIngestResult result = service.ingest(MORNING);

        assertThat(result.shortFailures()).isEqualTo(4);
        assertThat(result.midFailed()).isTrue();
        assertThat(result.inserted()).isZero();
        assertThat(result.updated()).isZero();
        assertThat(repository.count()).isEqualTo(32);
    }

    // ── 픽스처 ─────────────────────────────────────────────────────

    private WeatherForecast latest(Region region, LocalDate day) {
        return repository.findFirstByRegionIdAndForecastAtAndGranularityOrderByBaseAtDesc(
                region.getId(), PlaceNameNormalizer.jejuDayToUtc(day), WeatherGranularity.DAILY).orElseThrow();
    }

    /**
     * 단기예보 시간별 행 흉내. 격자 y(ny)로 권역 값을 갈라 "권역마다 따로 불렀는지"를 검증한다.
     * 날짜 오프셋 i: TMN 10+i+(ny-30), TMX 20+i+(ny-30), POP 20/60, 정오 SKY 1, 둘째 날만 PTY 1(비).
     */
    private static List<ShortTermItem> shortItems(int ny, LocalDate from, int days) {
        List<ShortTermItem> items = new ArrayList<>();
        int gridOffset = ny - 30;
        for (int i = 0; i < days; i++) {
            String date = from.plusDays(i).format(YMD);
            items.add(new ShortTermItem("TMN", date, "0600", String.valueOf(10 + i + gridOffset) + ".0"));
            items.add(new ShortTermItem("TMX", date, "1500", String.valueOf(20 + i + gridOffset) + ".0"));
            items.add(new ShortTermItem("TMP", date, "0900", String.valueOf(15 + i + gridOffset)));
            items.add(new ShortTermItem("POP", date, "0900", "20"));
            items.add(new ShortTermItem("POP", date, "1200", "60"));
            items.add(new ShortTermItem("SKY", date, "1200", "1"));
            items.add(new ShortTermItem("PTY", date, "1200", i == 2 ? "1" : "0"));
        }
        return items;
    }

    private static MidTaItem midTa() {
        return new MidTaItem(20, 26, 21, 27, 22, 28, 23, 29, 24, 30);
    }

    /** 오전: 맑음/구름많음/흐리고 비/맑음/흐리고 비·눈, 오후: +6일만 '흐리고 비' 60%로 오전과 다르게 - 병합 검증용 */
    private static MidLandItem midLand() {
        return new MidLandItem("맑음", "구름많음", "흐리고 비", "맑음", "흐리고 비/눈",
                10, 20, 70, 10, 40,
                "구름많음", "맑음", "흐리고 비", "흐리고 비/눈",
                20, 20, 60, 40);
    }
}
