package com.example.hangat.domain.weather;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.domain.weather.model.DailyWeather;
import com.example.hangat.domain.weather.model.MidLandItem;
import com.example.hangat.domain.weather.model.MidTaItem;
import com.example.hangat.domain.weather.model.ShortTermItem;
import com.example.hangat.domain.weather.model.entity.WeatherForecast;
import com.example.hangat.domain.weather.model.enums.WeatherGranularity;
import com.example.hangat.domain.weather.repository.WeatherForecastRepository;
import com.example.hangat.map.model.entity.Region;
import com.example.hangat.map.repository.RegionRepository;
import com.example.hangat.map.service.PlaceNameNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 주간(7일) 날씨 조립 - 메인 MAIN 날씨 카드와 샘플 코스 배치의 비 예보 판단이 쓴다.
 *
 * <p><b>DB 우선, 라이브 폴백.</b> {@code weather_forecasts}에 적재된 최신 발표분을 읽고,
 * 해당 권역에 한 날짜도 없으면(적재 전·적재 장애) 예전처럼 기상청을 직접 부른다.
 * 두 경로의 하루 요약 규칙은 {@link WeatherDailySummarizer} 하나라 화면이 갈라지지 않는다.
 */
@Service
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);
    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    /**
     * 메인 주간 날씨의 기준 권역. DB 적재 전 라이브 호출이 제주시 격자(52/38)를 썼고 북부 권역 격자가
     * 그 값이라 화면 값이 그대로 이어진다. 권역별 날씨 레이어(MAP_005)는 {@link #getWeeklyForecast(String)}를 쓴다.
     */
    static final String MAIN_REGION_CODE = "NORTH";
    static final int WEEK_DAYS = 7;
    /**
     * 이보다 오래된 발표분은 '지금 예보'로 내보내지 않는다. 적재가 하루 두 번(03:30·06:30)이라 36시간이면
     * 두 번 연속 실패한 뒤다 - 그때는 낡은 값을 그럴듯하게 보여주느니 라이브 호출(메인)이나 '정보 없음'(권역)으로 간다.
     */
    static final Duration STALE_AFTER = Duration.ofHours(36);

    private final WeatherClient client;
    private final WeatherForecastRepository forecastRepository;
    private final RegionRepository regionRepository;

    public WeatherService(WeatherClient client,
                          WeatherForecastRepository forecastRepository,
                          RegionRepository regionRepository) {
        this.client = client;
        this.forecastRepository = forecastRepository;
        this.regionRepository = regionRepository;
    }

    public List<DailyWeather> getWeeklyForecast() {
        return getWeeklyForecast(MAIN_REGION_CODE);
    }

    /**
     * 오늘부터 7일. DB에 그 권역의 신선한({@link #STALE_AFTER}) 예보가 하나라도 있으면 DB만 쓴다 -
     * 빠진 날짜는 지어내지 않고 null('정보 없음').
     *
     * <p>하나도 없을 때의 폴백은 권역마다 다르다. 메인 기준 권역(북부)만 라이브 호출로 간다 - 라이브 호출은
     * 제주시 격자에 고정돼 있어 다른 권역 값이 아니다. 다른 권역은 7일 전부 null, 모르는 코드는 빈 목록이다.
     */
    public List<DailyWeather> getWeeklyForecast(String regionCode) {
        // KST 고정 - 서버 시계가 UTC(OCI 기본)면 KST 새벽에 now()가 어제 날짜라 발표분·주간 창이 하루 밀린다
        LocalDate today = LocalDate.now(KmaIssueTimes.KST);

        Optional<Region> region = regionRepository.findByCode(regionCode);
        if (region.isEmpty()) {
            log.warn("알 수 없는 권역 코드 {} - 주간 날씨를 만들지 않는다", regionCode);
            return List.of();
        }
        List<DailyWeather> stored = fromStore(region.get(), today);
        if (!stored.isEmpty()) {
            return stored;
        }
        if (MAIN_REGION_CODE.equals(regionCode)) {
            log.info("weather_forecasts에 {} 권역의 신선한 주간 예보가 없어 기상청 라이브 호출로 폴백한다", regionCode);
            return fetchLive(today);
        }
        return nullWeek(today);
    }

    private List<DailyWeather> fromStore(Region region, LocalDate today) {
        LocalDate last = today.plusDays(WEEK_DAYS - 1);
        LocalDateTime freshAfter = LocalDateTime.now(ZoneOffset.UTC).minus(STALE_AFTER);   // base_at은 UTC
        Map<LocalDate, WeatherForecast> byDate = new LinkedHashMap<>();
        for (WeatherForecast forecast : forecastRepository.findLatestPerDate(
                region.getId(),
                PlaceNameNormalizer.jejuDayToUtc(today),
                PlaceNameNormalizer.jejuDayToUtc(last),
                WeatherGranularity.DAILY)) {
            if (forecast.getBaseAt().isBefore(freshAfter)) {
                continue;   // 낡은 발표분은 지금 예보가 아니다
            }
            byDate.putIfAbsent(PlaceNameNormalizer.utcToJejuDay(forecast.getForecastAt()), forecast);
        }
        if (byDate.isEmpty()) {
            return List.of();
        }
        List<DailyWeather> week = new ArrayList<>(WEEK_DAYS);
        for (int offset = 0; offset < WEEK_DAYS; offset++) {
            LocalDate date = today.plusDays(offset);
            WeatherForecast forecast = byDate.get(date);
            week.add(forecast == null
                    ? new DailyWeather(date, null, null, null, null)
                    : new DailyWeather(date,
                            forecast.getTempMin() == null ? null : forecast.getTempMin().intValue(),
                            forecast.getTempMax() == null ? null : forecast.getTempMax().intValue(),
                            forecast.getSkyCode(),
                            forecast.rainProbabilityPercent()));
        }
        return week;
    }

    /** 값이 없는 한 주 - 라이브로 메우거나 다른 권역 값을 빌리지 않는다. */
    private static List<DailyWeather> nullWeek(LocalDate today) {
        List<DailyWeather> week = new ArrayList<>(WEEK_DAYS);
        for (int offset = 0; offset < WEEK_DAYS; offset++) {
            week.add(new DailyWeather(today.plusDays(offset), null, null, null, null));
        }
        return week;
    }

    /** 적재 전 경로 - 기상청 단기(D+0~3)·중기(D+4~6)를 직접 불러 병합한다. */
    private List<DailyWeather> fetchLive(LocalDate today) {
        // 오늘 발표분 실패 시 어제 발표분으로 1회 폴백
        List<ShortTermItem> shortTerm;
        try {
            shortTerm = client.fetchShortTerm(today.format(YMD), "0500");
        } catch (BaseException e) {
            shortTerm = client.fetchShortTerm(today.minusDays(1).format(YMD), "2300");
        }

        LocalDate midBaseDate = today;
        MidTaItem midTa;
        MidLandItem midLand;
        try {
            midTa = client.fetchMidTemperature(today.format(YMD) + "0600");
            midLand = client.fetchMidLand(today.format(YMD) + "0600");
        } catch (BaseException e) {
            midBaseDate = today.minusDays(1);
            midTa = client.fetchMidTemperature(midBaseDate.format(YMD) + "1800");
            midLand = client.fetchMidLand(midBaseDate.format(YMD) + "1800");
        }

        List<DailyWeather> week = new ArrayList<>(WEEK_DAYS);
        for (int offset = 0; offset <= 3; offset++) {
            week.add(WeatherDailySummarizer.fromShortTerm(today.plusDays(offset), shortTerm).toDailyWeather());
        }
        for (int offset = 4; offset <= 6; offset++) {
            week.add(WeatherDailySummarizer.fromMid(today.plusDays(offset), midBaseDate, midTa, midLand)
                    .toDailyWeather());
        }
        return week;
    }
}
