package com.example.hangat.course.service;

import com.example.hangat.common.geo.GeoService;
import com.example.hangat.course.model.entity.Course;
import com.example.hangat.course.model.entity.CourseItem;
import com.example.hangat.course.model.entity.CoursePreset;
import com.example.hangat.course.model.enums.CourseStatus;
import com.example.hangat.course.model.enums.CourseType;
import com.example.hangat.course.model.enums.GenerationReason;
import com.example.hangat.course.model.enums.Transport;
import com.example.hangat.course.repository.CourseItemRepository;
import com.example.hangat.course.repository.CoursePresetRepository;
import com.example.hangat.course.repository.CourseRepository;
import com.example.hangat.domain.congestion.CongestionService;
import com.example.hangat.domain.weather.WeatherService;
import com.example.hangat.domain.weather.model.DailyWeather;
import com.example.hangat.domain.weather.model.entity.WeatherForecast;
import com.example.hangat.domain.weather.model.enums.WeatherGranularity;
import com.example.hangat.domain.weather.repository.WeatherForecastRepository;
import com.example.hangat.map.model.entity.CongestionForecast;
import com.example.hangat.map.model.entity.Place;
import com.example.hangat.map.model.enums.CongestionLevel;
import com.example.hangat.map.repository.PlaceRepository;
import com.example.hangat.map.service.PlaceNameNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 메인 추천 코스(MAIN_002) 사전 생성 배치 - "시연 라이브 호출 금지" 원칙의 구현체.
 *
 * <p><b>카드 3장의 차별점(팀 결정 2026-08-31)</b>:
 * ① 그날 혼잡 예보가 가장 여유로운 3개 권역을 골라 서로 다른 권역 하나씩 - 매일 바뀌는 이유가
 * 곧 과밀 분산이라는 서비스 정체성이다. ② 비 예보 날은 실내 위주 배치. ③ 기간은 프리셋별
 * 1박2일~2박3일.
 *
 * <p><b>LLM이 아니다</b> - 혼잡 예보를 하드 제약으로 넣는 결정론적 규칙이며, 그래서
 * recommendation_score는 채우지 않는다(엔진 점수가 아닌 값을 점수인 척 넣지 않는다).
 * 한별님 생성 엔진이 완성되면 이 배치의 선발부만 엔진 호출로 교체할 수 있게 분리해 뒀다.
 *
 * <p><b>실패 내성</b>: 프리셋 하나가 실패해도 나머지는 커밋되고(개별 try-catch),
 * 조회가 "프리셋별 최신 READY"라 배치 전체가 죽어도 어제 코스가 남아 카드는 비지 않는다.
 */
@Service
public class SampleCourseGenerator {

    private static final Logger log = LoggerFactory.getLogger(SampleCourseGenerator.class);

    /** 하루 방문지 수 - 여유 있는 분산 여행 컨셉이라 3곳으로 고정(빽빽한 코스는 컨셉 배반). */
    private static final int SPOTS_PER_DAY = 3;
    /** 카드 수 = 서로 다른 권역 수. */
    private static final int REGIONS_TO_PICK = 3;
    /** 이 이상이면 그날은 "비 예보"로 보고 실내 우선 배치. 기상청 강수확률(%) 기준. */
    private static final int RAINY_PROB_FROM = 60;
    /** 동선 묶기 전 저혼잡 후보 풀 크기 - 너무 크면 동선이 저혼잡을 이기고, 너무 작으면 다 몰린다. */
    private static final int ROUTE_POOL_SIZE = 10;

    private static final String TOURIST_CODE = "TOURIST";

    private final CoursePresetRepository presetRepository;
    private final CourseRepository courseRepository;
    private final CourseItemRepository itemRepository;
    private final PlaceRepository placeRepository;
    private final CongestionService congestionService;
    private final WeatherService weatherService;
    private final WeatherForecastRepository weatherForecastRepository;
    private final GeoService geoService;
    private final CourseTravelCalculator travelCalculator;

    public SampleCourseGenerator(CoursePresetRepository presetRepository,
                                 CourseRepository courseRepository,
                                 CourseItemRepository itemRepository,
                                 PlaceRepository placeRepository,
                                 CongestionService congestionService,
                                 WeatherService weatherService,
                                 WeatherForecastRepository weatherForecastRepository,
                                 GeoService geoService,
                                 CourseTravelCalculator travelCalculator) {
        this.presetRepository = presetRepository;
        this.courseRepository = courseRepository;
        this.itemRepository = itemRepository;
        this.placeRepository = placeRepository;
        this.congestionService = congestionService;
        this.weatherService = weatherService;
        this.weatherForecastRepository = weatherForecastRepository;
        this.geoService = geoService;
        this.travelCalculator = travelCalculator;
    }

    /** 한 번의 배치 결과 - 로그·수동 실행 응답용. */
    public record RunSummary(LocalDate startDate, List<String> readyRegions, List<String> failedRegions,
                             String skippedReason) {
        static RunSummary skipped(LocalDate date, String reason) {
            return new RunSummary(date, List.of(), List.of(), reason);
        }
    }

    /**
     * startDate 출발 샘플 코스 생성. 스케줄러는 내일 날짜로 부른다 -
     * 새벽 4시 생성 시점에 출발일 예보가 가장 확실한 날이 내일이다.
     *
     * <p>트랜잭션 하나로 묶는다: 프리셋별 실패는 안에서 잡아 FAILED로 남기므로
     * 예외가 밖으로 튀어 전체가 구르는 일은 코드 버그뿐이다.
     */
    @Transactional
    public RunSummary generate(LocalDate startDate) {
        Map<Long, CongestionForecast> startForecasts = congestionService.forecastsFor(startDate);
        if (startForecasts.isEmpty()) {
            log.warn("샘플 코스 생성 스킵 - {} 혼잡 예보 없음 (적재 배치 선행 필요)", startDate);
            return RunSummary.skipped(startDate, "NO_FORECAST");
        }

        Map<String, List<Place>> candidatesByRegion = touristCandidatesByRegion(startForecasts);
        List<String> rankedRegions = viableRegionsRanked(candidatesByRegion, startForecasts);
        if (rankedRegions.isEmpty()) {
            log.warn("샘플 코스 생성 스킵 - {} 저혼잡 후보 권역 없음", startDate);
            return RunSummary.skipped(startDate, "NO_CALM_REGION");
        }

        Map<LocalDate, DailyWeather> weather = weatherByDate();

        // 한산한 순서로 훑되 3장을 채울 때까지 다음 권역으로 넘어간다 - 한 권역이
        // 2·3일차 데이터 부족으로 실패해도 그 자리가 비지 않는다(재선발).
        List<String> ready = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (String regionCode : rankedRegions) {
            if (ready.size() >= REGIONS_TO_PICK) {
                break;
            }
            CoursePreset preset = ensurePreset(regionCode);
            // 같은 출발일 READY가 이미 있으면 재생성하지 않는다 - 재기동·수동 재실행이 행을 쌓지 않는 멱등 장치
            if (courseRepository.existsByPresetIdAndCourseTypeAndStatusAndStartDate(
                    preset.getId(), CourseType.SAMPLE, CourseStatus.READY, startDate)) {
                ready.add(regionCode);
                continue;
            }
            Course course = courseRepository.save(Course.builder()
                    .preset(preset)
                    .courseType(CourseType.SAMPLE)
                    .generationReason(GenerationReason.SAMPLE_BATCH)
                    .title(preset.getDefaultTitle())
                    .startDate(startDate)
                    .endDate(startDate.plusDays(preset.getDurationDays() - 1))
                    .people(preset.getDefaultPeople())
                    .transport(preset.getDefaultTransport() != null
                            ? preset.getDefaultTransport() : Transport.RENTAL_CAR)
                    .algorithmVersion("sample-rule-v1")
                    .build());
            try {
                buildItinerary(course, regionCode, startDate, preset.getDurationDays(), weather);
                course.markReady();
                ready.add(regionCode);
            } catch (NotEnoughCandidatesException e) {
                log.warn("샘플 코스 실패({}) - {}", regionCode, e.getMessage());
                // 앞 일차에 이미 저장된 아이템을 지운다 - FAILED 코스에 고아 일정이 누적되면 안 된다
                itemRepository.deleteByCourse(course.getId());
                course.markFailed("NOT_ENOUGH_CANDIDATES");
                failed.add(regionCode);
            }
        }
        log.info("샘플 코스 생성 완료 - 출발일 {} / 성공 {} / 실패 {}", startDate, ready, failed);
        return new RunSummary(startDate, ready, failed, null);
    }

    /** 출발일 예보가 있는 관광지들을 권역별로 - 권역 랭킹과 후보 선발의 공통 재료. */
    private Map<String, List<Place>> touristCandidatesByRegion(Map<Long, CongestionForecast> forecasts) {
        Map<String, List<Place>> byRegion = new HashMap<>();
        for (Place place : placeRepository.findAllById(forecasts.keySet())) {
            if (!TOURIST_CODE.equals(place.getPrimaryCategory().getCode())) continue;
            if (place.getLatitude() == null || place.getLongitude() == null) continue;
            byRegion.computeIfAbsent(place.getRegion().getCode(), k -> new ArrayList<>()).add(place);
        }
        return byRegion;
    }

    /**
     * 생성 가능한 권역을 출발일 평균 집중률 낮은 순으로 전부 - "오늘 가장 한산한 권역"이 매일의 차별점이다.
     *
     * <p>생존성 필터: 출발일 기준 <b>혼잡 미만 후보가 기간×하루 3곳 이상</b>인 권역만.
     * 이틀째 이후 예보는 출발일과 다를 수 있어 완전 보장은 아니지만(그건 생성 중 FAILED로 잡혀
     * 재선발된다), "하루치 3곳"만 보던 필터가 다일 코스에서 확정 실패를 뽑는 사고를 막는다.
     * 프리셋 정의가 없는 권역 코드는 만들 수 없으므로 랭킹에서도 뺀다.
     */
    private List<String> viableRegionsRanked(Map<String, List<Place>> byRegion,
                                             Map<Long, CongestionForecast> forecasts) {
        record RegionAvg(String code, double avg) {
        }
        return byRegion.entrySet().stream()
                .filter(e -> PRESET_SPECS.containsKey(e.getKey()))
                .filter(e -> {
                    long calm = e.getValue().stream()
                            .filter(p -> levelOf(forecasts, p) != CongestionLevel.CROWDED)
                            .count();
                    return calm >= (long) SPOTS_PER_DAY * PRESET_SPECS.get(e.getKey()).days();
                })
                .map(e -> new RegionAvg(e.getKey(), e.getValue().stream()
                        .mapToDouble(p -> forecasts.get(p.getId()).getRate().doubleValue())
                        .average().orElse(100.0)))
                .sorted(Comparator.comparingDouble(RegionAvg::avg))
                .map(RegionAvg::code)
                .toList();
    }

    private CongestionLevel levelOf(Map<Long, CongestionForecast> forecasts, Place place) {
        return CongestionLevel.from(forecasts.get(place.getId()).getRate());
    }

    /** 날씨는 보조 신호 - 호출 실패 시 날씨 가중 없이 진행한다(코스가 안 나가는 것보다 낫다). */
    private Map<LocalDate, DailyWeather> weatherByDate() {
        try {
            Map<LocalDate, DailyWeather> byDate = new LinkedHashMap<>();
            for (DailyWeather day : weatherService.getWeeklyForecast()) {
                byDate.put(day.date(), day);
            }
            return byDate;
        } catch (Exception e) {
            log.warn("날씨 조회 실패 - 실내/실외 가중 없이 생성한다: {}", e.getMessage());
            return Map.of();
        }
    }

    private void buildItinerary(Course course, String regionCode, LocalDate startDate,
                                int days, Map<LocalDate, DailyWeather> weather) {
        Set<Long> used = new HashSet<>();
        double totalRate = 0;
        int itemCount = 0;

        for (int dayNo = 1; dayNo <= days; dayNo++) {
            LocalDate date = startDate.plusDays(dayNo - 1);
            Map<Long, CongestionForecast> forecasts = congestionService.forecastsFor(date);
            boolean rainy = isRainy(weather.get(date));

            List<Place> pool = dayPool(regionCode, forecasts, used, rainy);
            if (pool.size() < SPOTS_PER_DAY) {
                throw new NotEnoughCandidatesException(
                        regionCode + " " + date + " 후보 " + pool.size() + "곳 (필요 " + SPOTS_PER_DAY + ")");
            }

            // 비 예보 날은 선발부터 실내를 확정한다 - 풀 정렬만으로는 동선 그리디가
            // 2·3번째 슬롯에서 실외를 집어 "실내 위주" 약속이 첫 슬롯 하나로 끝난다
            List<Place> route = greedyRoute(rainy ? indoorPriorityPick(pool) : pool);
            Place previous = null;
            for (int position = 1; position <= SPOTS_PER_DAY; position++) {
                Place place = route.get(position - 1);
                CongestionForecast forecast = forecasts.get(place.getId());
                itemRepository.save(courseItem(course, place, dayNo, position, date,
                        forecast, previous, rainy));
                used.add(place.getId());
                totalRate += forecast.getRate().doubleValue();
                itemCount++;
                previous = place;
            }
        }

        // 비용 캐시는 채우지 않는다 - 입장료 실측치가 없어(usefee는 자유 텍스트) 추정치를
        // 지어내면 정직성 원칙 위반이다. 카드가 null을 "요금 확인 필요"로 표시한다.
        BigDecimal avgRate = BigDecimal.valueOf(totalRate / itemCount).setScale(2, RoundingMode.HALF_UP);
        course.updateAggregates(null, null, avgRate);
    }

    private boolean isRainy(DailyWeather day) {
        return day != null && day.rainProb() != null && day.rainProb() >= RAINY_PROB_FROM;
    }

    /**
     * 그날 후보: 같은 권역 + 예보 존재 + 혼잡 미만 + 미사용, 집중률 오름차순 상위 풀.
     * 비 예보 날은 실내 후보가 풀 앞쪽에 오고, 선발 확정은 {@link #indoorPriorityPick}이 한다.
     * 실내가 하루 정원보다 모자라면 실외로 채워지므로 "실내 위주"까지만 약속한다.
     */
    private List<Place> dayPool(String regionCode, Map<Long, CongestionForecast> forecasts,
                                Set<Long> used, boolean rainy) {
        Comparator<Place> byRate = Comparator.comparingDouble(
                p -> forecasts.get(p.getId()).getRate().doubleValue());
        Comparator<Place> order = rainy
                ? Comparator.comparing((Place p) -> !IndoorClassifier.isIndoor(p)).thenComparing(byRate)
                : byRate;

        return placeRepository.findAllById(forecasts.keySet()).stream()
                .filter(p -> TOURIST_CODE.equals(p.getPrimaryCategory().getCode()))
                .filter(p -> regionCode.equals(p.getRegion().getCode()))
                .filter(p -> p.getLatitude() != null && p.getLongitude() != null)
                .filter(p -> !used.contains(p.getId()))
                // 혼잡 컷(#과밀지역 우회) - 경계값의 단일 출처는 map CongestionLevel 하나다
                .filter(p -> levelOf(forecasts, p) != CongestionLevel.CROWDED)
                .sorted(order)
                .limit(ROUTE_POOL_SIZE)
                .toList();
    }

    /** 비 예보 날 선발 - 실내 후보(집중률순)를 하루 정원까지 먼저 확정하고, 모자란 만큼만 실외로 채운다. */
    private List<Place> indoorPriorityPick(List<Place> pool) {
        List<Place> picked = new ArrayList<>(pool.stream()
                .filter(IndoorClassifier::isIndoor)
                .limit(SPOTS_PER_DAY)
                .toList());
        for (Place place : pool) {
            if (picked.size() >= SPOTS_PER_DAY) break;
            if (!picked.contains(place)) picked.add(place);
        }
        return picked;
    }

    /**
     * 동선 묶기 - 풀 첫 장소(가장 한산/실내 우선)를 닻으로, 남은 후보 중 가장 가까운 곳을
     * 그리디로 잇는다. 최적해는 아니지만 3곳 규모에선 충분하고 결정론적이다.
     */
    private List<Place> greedyRoute(List<Place> pool) {
        List<Place> remaining = new ArrayList<>(pool);
        List<Place> route = new ArrayList<>();
        Place current = remaining.remove(0);
        route.add(current);
        while (route.size() < SPOTS_PER_DAY) {
            Place from = current;
            Place next = remaining.stream()
                    .min(Comparator.comparingDouble(p -> geoService.distanceKm(
                            from.getLatitude(), from.getLongitude(), p.getLatitude(), p.getLongitude())))
                    .orElseThrow();
            remaining.remove(next);
            route.add(next);
            current = next;
        }
        return route;
    }

    private CourseItem courseItem(Course course, Place place, int dayNo, int position,
                                  LocalDate date, CongestionForecast forecast,
                                  Place previous, boolean rainy) {
        // 스왑과 같은 계산기를 쓴다 - 배치와 스왑의 이동시간이 어긋나면 화면에서 티가 난다
        CourseTravelCalculator.Travel travel = travelCalculator.between(previous, place);
        boolean indoor = IndoorClassifier.isIndoor(place);
        return CourseItem.builder()
                .course(course).place(place)
                .dayNo((short) dayNo).position((short) position)
                .visitDate(date)
                .plannedCongestionForecast(forecast)
                .plannedWeatherForecast(weatherSnapshot(place, date))
                .inboundDistanceM(travel.distanceM()).inboundTravelMinutes(travel.minutes())
                // 근거 코드는 명세서·프론트 union 목록(CONGESTION/STYLE/GOOD_PRICE/HIDDEN_GEM/ROUTE) 안에서만 쓴다
                // - 날씨 사유는 코드가 아니라 아래 문구가 설명한다
                .recommendationReasonCode("CONGESTION")
                .recommendationReason(reasonFor(place, forecast, rainy, indoor))
                .build();
    }

    /**
     * 저장 시점 날씨 스냅숏 - 장소 권역·방문 날짜의 최신 발표분(DAILY). 없으면 null('날씨 정보 없음').
     * 혼잡 스냅숏과 같은 뜻이다: 재열람 때 "그때 예보 vs 지금 예보"를 병기할 근거.
     * 비 예보 판단({@link #isRainy})은 아직 주간 조회(북부 기준)를 쓴다 - 권역별 전환은 후속.
     */
    private WeatherForecast weatherSnapshot(Place place, LocalDate date) {
        if (place.getRegion() == null) {
            return null;
        }
        return weatherForecastRepository.findFirstByRegionIdAndForecastAtAndGranularityOrderByBaseAtDesc(
                        place.getRegion().getId(), PlaceNameNormalizer.jejuDayToUtc(date), WeatherGranularity.DAILY)
                .orElse(null);
    }

    /** 근거 문구 - 한산 장소 카드(MainService)와 같은 우선순위 + 날씨 사유 추가. */
    private String reasonFor(Place place, CongestionForecast forecast, boolean rainy, boolean indoor) {
        if (rainy && indoor) return "비 예보가 있어 실내 위주로 담았어요";
        if (place.isGoodPrice()) return "착한가격업소 검증가";
        if (place.isHiddenGem()) return "덜 알려진 숨은 명소";
        if (CongestionLevel.from(forecast.getRate()) == CongestionLevel.QUIET) {
            return "이 날짜 혼잡 예보가 여유예요";
        }
        return "인기 명소보다 한산한 편이에요";
    }

    /**
     * 권역별 프리셋 - 마스터 초기화 관례(없으면 넣고 있으면 둔다, MapMasterDataInitializer)를 따른다.
     * 기간 배분(최소 1박2일~최대 2박3일 팀 결정): 동부·남부 = 2박3일, 북부·서부 = 1박2일.
     * 4권역 중 3곳을 뽑으므로 이렇게 갈라 두면 <b>어떤 날이든 두 기간이 카드에 섞인다</b>
     * (탈락은 1곳뿐이라 2박3일 조와 1박2일 조가 동시에 전멸할 수 없다).
     */
    private CoursePreset ensurePreset(String regionCode) {
        String code = "SAMPLE_" + regionCode;
        return presetRepository.findByCode(code).orElseGet(() -> {
            PresetSpec spec = PRESET_SPECS.get(regionCode);
            return presetRepository.save(CoursePreset.builder()
                    .code(code)
                    .name(spec.name())
                    .defaultTitle(spec.title())
                    .durationDays(spec.days())
                    .defaultTransport(Transport.RENTAL_CAR)
                    .filterJson("{\"regionCode\":\"" + regionCode + "\"}")
                    .build());
        });
    }

    private record PresetSpec(String name, String title, short days) {
    }

    private static final Map<String, PresetSpec> PRESET_SPECS = Map.of(
            "NORTH", new PresetSpec("북부 샘플 코스", "북부 느긋 1박 2일", (short) 2),
            "EAST", new PresetSpec("동부 샘플 코스", "동부 한산 2박 3일", (short) 3),
            "SOUTH", new PresetSpec("남부 샘플 코스", "남부 여유 2박 3일", (short) 3),
            "WEST", new PresetSpec("서부 샘플 코스", "서부 고요 1박 2일", (short) 2));

    /** 데이터 부족은 코드 버그가 아니라 정상 실패 경로 - FAILED로 남기고 다음 프리셋으로 간다. */
    static class NotEnoughCandidatesException extends RuntimeException {
        NotEnoughCandidatesException(String message) {
            super(message);
        }
    }
}
