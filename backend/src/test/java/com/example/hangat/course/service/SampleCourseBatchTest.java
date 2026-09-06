package com.example.hangat.course.service;

import com.example.hangat.course.model.CourseSummaryResponse;
import com.example.hangat.course.model.entity.Course;
import com.example.hangat.course.model.entity.CourseItem;
import com.example.hangat.course.model.entity.CoursePreset;
import com.example.hangat.course.model.enums.CourseStatus;
import com.example.hangat.course.model.enums.CourseType;
import com.example.hangat.course.model.enums.Transport;
import com.example.hangat.course.repository.CourseItemRepository;
import com.example.hangat.course.repository.CoursePresetRepository;
import com.example.hangat.course.repository.CourseRepository;
import com.example.hangat.domain.weather.WeatherService;
import com.example.hangat.domain.weather.model.DailyWeather;
import com.example.hangat.domain.weather.model.entity.WeatherForecast;
import com.example.hangat.domain.weather.model.enums.PrecipitationType;
import com.example.hangat.domain.weather.model.enums.WeatherGranularity;
import com.example.hangat.domain.weather.repository.WeatherForecastRepository;
import com.example.hangat.map.model.entity.CongestionForecast;
import com.example.hangat.map.model.entity.DataSource;
import com.example.hangat.map.model.entity.Place;
import com.example.hangat.map.model.entity.PlaceCategory;
import com.example.hangat.map.model.entity.Region;
import com.example.hangat.map.repository.CongestionForecastRepository;
import com.example.hangat.map.repository.PlaceCategoryRepository;
import com.example.hangat.map.repository.PlaceRepository;
import com.example.hangat.map.repository.RegionRepository;
import com.example.hangat.map.service.PlaceNameNormalizer;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 샘플 코스 배치 + 메인 카드 API 검증 (MAIN_002).
 *
 * <p>시드 시나리오: 남부(평균 15.6, 혼잡 미끼 1곳 포함)·동부(26.3, 실내 2곳)·서부(29)가 한산,
 * 북부는 전부 혼잡(생존 불가). 출발일은 비 예보(80%) - 실내 우선 배치까지 본다.
 * 프리셋은 <b>평균 집중률 역순(서→동→남)으로 미리 만들어</b> 카드 정렬이 프리셋 id 순서와
 * 우연히 일치해 통과하는 허위 통과를 막는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SampleCourseBatchTest {

    private static final LocalDate 출발일 = LocalDate.of(2026, 9, 5);
    private static final LocalDateTime 발표버전 = LocalDateTime.of(2026, 9, 4, 0, 0);
    private static final int 시드일수 = 4;   // 출발일+1 배치(2박3일)까지 돌릴 수 있게

    @Autowired MockMvc mockMvc;
    @Autowired EntityManager em;
    @Autowired SampleCourseGenerator generator;
    @Autowired MainCourseService mainCourseService;
    @Autowired CourseRepository courseRepository;
    @Autowired CourseItemRepository itemRepository;
    @Autowired CoursePresetRepository presetRepository;
    @Autowired PlaceRepository placeRepository;
    @Autowired CongestionForecastRepository forecastRepository;
    @Autowired RegionRepository regionRepository;
    @Autowired PlaceCategoryRepository categoryRepository;
    @Autowired WeatherForecastRepository weatherForecastRepository;

    @MockitoBean WeatherService weatherService;

    private PlaceCategory tourist;
    private DataSource source;
    private List<Long> 동부실내Ids;
    private Long 남부혼잡미끼Id;

    @BeforeEach
    void seed() {
        forecastRepository.deleteAll();

        // 마스터는 전부 방어적으로 - MapMasterDataInitializer 유무·순서에 기대지 않는다(스위트 관례)
        tourist = categoryRepository.findByCode("TOURIST")
                .orElseGet(() -> categoryRepository.save(PlaceCategory.builder()
                        .code("TOURIST").name("관광지").build()));
        source = em.find(DataSource.class, "KTO_CNCTR");
        if (source == null) {
            source = DataSource.builder()
                    .code("KTO_CNCTR").displayName("한국관광공사 관광지별 집중률")
                    .providerName("한국관광공사").attributionText("한국관광공사")
                    .displayOrder((short) 2).isActive(true)
                    .build();
            em.persist(source);
        }

        // 프리셋을 평균 집중률 "역순"으로 선생성 - 정렬 검증이 id 순서에 업히지 못하게
        preset("SAMPLE_WEST", "서부 샘플 코스", "서부 고요 1박 2일", (short) 2);
        preset("SAMPLE_EAST", "동부 샘플 코스", "동부 한산 2박 3일", (short) 3);
        preset("SAMPLE_SOUTH", "남부 샘플 코스", "남부 여유 2박 3일", (short) 3);

        // 남부: 가장 한산(5~13) + 혼잡 미끼 75 한 곳 - 미끼가 코스에 들어오면 혼잡 컷이 죽은 것
        남부혼잡미끼Id = seedRegion("SOUTH", 33.25, 126.50,
                new double[]{5, 6, 7, 8, 9, 10, 11, 12, 13, 75}, 0, "남부 혼잡미끼").decoyId();
        // 동부: 실내 2곳(집중률 60·65 - 실내 우선이 더 한산한 실외를 이기는지) + 실외 7곳
        동부실내Ids = seedRegion("EAST", 33.45, 126.90,
                new double[]{60, 65, 10, 12, 14, 16, 18, 20, 22}, 2, null).indoorIds();
        // 서부: 보통 수준
        seedRegion("WEST", 33.34, 126.25,
                new double[]{25, 26, 27, 28, 29, 30, 31, 32, 33}, 0, null);
        // 북부: 전부 혼잡 - 여유 후보 0곳이라 생존성 필터에서 빠져야 한다
        seedRegion("NORTH", 33.50, 126.52, new double[]{85, 88, 91, 94}, 0, null);

        given(weatherService.getWeeklyForecast()).willReturn(List.of(
                new DailyWeather(출발일, 22, 27, "흐림", 80),            // 출발일 = 비 예보
                new DailyWeather(출발일.plusDays(1), 23, 28, "맑음", 10),
                new DailyWeather(출발일.plusDays(2), 23, 29, "맑음", 0),
                new DailyWeather(출발일.plusDays(3), 24, 29, "맑음", null)));   // 강수확률 결측도 흔하다
    }

    private void preset(String code, String name, String title, short days) {
        presetRepository.findByCode(code).orElseGet(() -> presetRepository.save(CoursePreset.builder()
                .code(code).name(name).defaultTitle(title).durationDays(days)
                .defaultTransport(Transport.RENTAL_CAR)
                .build()));
    }

    private record Seeded(List<Long> indoorIds, Long decoyId) {
    }

    /**
     * 권역 하나에 장소·시드일수치 예보를 심는다.
     * 앞쪽 indoorCount개 rate는 실내 장소("전시관" - 분류기 키워드), decoyName이 있으면
     * 마지막 rate가 그 이름의 실외 장소가 된다.
     */
    private Seeded seedRegion(String regionCode, double baseLat, double baseLng,
                              double[] rates, int indoorCount, String decoyName) {
        Region region = regionRepository.findByCode(regionCode).orElseThrow();
        List<Long> indoorIds = new java.util.ArrayList<>();
        Long decoyId = null;
        for (int i = 0; i < rates.length; i++) {
            boolean indoor = i < indoorCount;
            boolean decoy = decoyName != null && i == rates.length - 1;
            String name = indoor ? regionCode + " 전시관" + (i + 1)
                    : decoy ? decoyName
                    : regionCode + " 야외명소" + (i + 1);
            Place place = placeRepository.save(Place.builder()
                    .name(name).normalizedName(name)
                    .region(region).primaryCategory(tourist)
                    .latitude(BigDecimal.valueOf(baseLat + i * 0.01))
                    .longitude(BigDecimal.valueOf(baseLng + i * 0.01))
                    .isGoodPrice(false).isHiddenGem(false).reviewCount(0)
                    .build());
            if (indoor) {
                indoorIds.add(place.getId());
            }
            if (decoy) {
                decoyId = place.getId();
            }
            for (int day = 0; day < 시드일수; day++) {
                forecastRepository.save(CongestionForecast.of(place, source,
                        PlaceNameNormalizer.jejuDayToUtc(출발일.plusDays(day)), 발표버전,
                        BigDecimal.valueOf(rates[i])));
            }
        }
        em.flush();
        return new Seeded(indoorIds, decoyId);
    }

    @Test
    void 한산한_세_권역이_카드가_되고_혼잡_권역은_생존성에서_빠진다() {
        SampleCourseGenerator.RunSummary summary = generator.generate(출발일);

        assertThat(summary.readyRegions()).containsExactly("SOUTH", "EAST", "WEST");
        assertThat(summary.failedRegions()).isEmpty();

        List<Course> ready = courseRepository.findAll().stream()
                .filter(c -> c.getStatus() == CourseStatus.READY).toList();
        assertThat(ready).hasSize(3);
        assertThat(ready).allSatisfy(c -> {
            assertThat(c.getCourseType()).isEqualTo(CourseType.SAMPLE);
            assertThat(c.getUser()).isNull();
            assertThat(c.getAverageCongestionRate()).isNotNull();
            assertThat(c.getEstimatedCostMin()).isNull();   // 지어내지 않은 비용
        });
    }

    @Test
    void 혼잡_장소는_가장_한산한_권역에서도_코스에_들어오지_않는다() {
        generator.generate(출발일);
        Course 남부 = courseByTitle("남부 여유 2박 3일");

        List<CourseItem> items = itemRepository.findItemsWithPlace(남부.getId());
        assertThat(items).hasSize(9);
        assertThat(items.stream().map(i -> i.getPlace().getId()))
                .doesNotContain(남부혼잡미끼Id)                      // 집중률 75 - 혼잡 컷
                .doesNotHaveDuplicates();
        assertThat(items.get(0).getInboundDistanceM()).isNull();      // 첫 슬롯
        assertThat(items.get(1).getInboundDistanceM()).isPositive();
        assertThat(items).allSatisfy(i -> {
            assertThat(i.getPlannedCongestionForecast()).isNotNull(); // 발표 버전 스냅숏
            assertThat(i.getRecommendationScore()).isNull();          // 규칙 기반 - 점수를 지어내지 않는다
            assertThat(i.getVisitDate()).isEqualTo(출발일.plusDays(i.getDayNo() - 1));
        });
    }

    @Test
    void 저장_시점_날씨_스냅숏은_그_권역_날짜의_최신_발표분을_가리키고_없으면_null이다() {
        DataSource kmaShort = em.find(DataSource.class, "KMA_SHORT");
        if (kmaShort == null) {
            kmaShort = DataSource.builder()
                    .code("KMA_SHORT").displayName("기상청 단기예보")
                    .providerName("기상청").attributionText("기상청")
                    .displayOrder((short) 6).isActive(true)
                    .build();
            em.persist(kmaShort);
        }
        Region south = regionRepository.findByCode("SOUTH").orElseThrow();
        LocalDateTime 어제발표 = LocalDateTime.of(2026, 9, 3, 20, 0);
        LocalDateTime 오늘발표 = LocalDateTime.of(2026, 9, 4, 20, 0);
        for (int day = 0; day < 3; day++) {
            weatherForecastRepository.save(WeatherForecast.daily(south, kmaShort,
                    PlaceNameNormalizer.jejuDayToUtc(출발일.plusDays(day)), 오늘발표,
                    "맑음", PrecipitationType.NONE, 22, 28, 10));
        }
        weatherForecastRepository.save(WeatherForecast.daily(south, kmaShort,
                PlaceNameNormalizer.jejuDayToUtc(출발일), 어제발표, "비", PrecipitationType.RAIN, 20, 25, 80));
        em.flush();

        generator.generate(출발일);

        List<CourseItem> 남부 = itemRepository.findItemsWithPlace(courseByTitle("남부 여유 2박 3일").getId());
        assertThat(남부).hasSize(9).allSatisfy(i -> {
            assertThat(i.getPlannedWeatherForecast()).isNotNull();
            assertThat(i.getPlannedWeatherForecast().getRegion().getCode()).isEqualTo("SOUTH");
            assertThat(i.getPlannedWeatherForecast().getForecastAt())
                    .isEqualTo(PlaceNameNormalizer.jejuDayToUtc(i.getVisitDate()));
            assertThat(i.getPlannedWeatherForecast().getBaseAt()).isEqualTo(오늘발표);   // 어제 발표분이 아니라 최신
        });
        // 예보가 없는 권역은 지어내지 않는다 - '날씨 정보 없음'
        List<CourseItem> 동부 = itemRepository.findItemsWithPlace(courseByTitle("동부 한산 2박 3일").getId());
        assertThat(동부).isNotEmpty().allSatisfy(i -> assertThat(i.getPlannedWeatherForecast()).isNull());

        // 발표 버전을 지워도(수동 정리) 코스가 막지 않고 스냅숏만 '정보 없음'으로 돌아간다 - ON DELETE SET NULL
        // (혼잡 스냅숏의 CourseDomainTest와 같은 규칙. 적재는 삭제 대신 값 갱신이라 평소엔 일어나지 않는다)
        weatherForecastRepository.deleteVersion(오늘발표, WeatherGranularity.DAILY);
        em.flush();
        em.clear();
        List<CourseItem> 남부_이후 = itemRepository.findItemsWithPlace(courseByTitle("남부 여유 2박 3일").getId());
        assertThat(남부_이후).hasSize(9).allSatisfy(i -> assertThat(i.getPlannedWeatherForecast()).isNull());
    }

    @Test
    void 비_예보_날은_실내_후보를_정원까지_먼저_확정한다() {
        generator.generate(출발일);
        Course 동부 = courseByTitle("동부 한산 2박 3일");

        List<CourseItem> items = itemRepository.findItemsWithPlace(동부.getId());
        List<CourseItem> 첫날 = items.stream().filter(i -> i.getDayNo() == 1).toList();

        // 실내 2곳(집중률 60·65)이 더 한산한 실외들을 제치고 첫날에 모두 들어와야 "실내 위주"다
        assertThat(첫날.stream().map(i -> i.getPlace().getId()))
                .containsAll(동부실내Ids);
        assertThat(첫날.stream()
                .filter(i -> 동부실내Ids.contains(i.getPlace().getId()))
                .map(CourseItem::getRecommendationReason))
                .allSatisfy(reason -> assertThat(reason).isEqualTo("비 예보가 있어 실내 위주로 담았어요"));

        // 둘째 날부터는 비가 안 오니 근거가 날씨가 아니어야 한다
        assertThat(items.stream().filter(i -> i.getDayNo() >= 2))
                .allSatisfy(i -> assertThat(i.getRecommendationReasonCode()).isEqualTo("CONGESTION"));
    }

    @Test
    void 이틀째_예보가_빠진_권역은_실패하고_고아_일정_없이_다음_권역이_재선발된다() {
        // 서부의 2일차(출발일+1) 예보만 제거 - 부분 적재 상황
        em.createQuery("""
                        delete from CongestionForecast f
                        where f.forecastAt = :t and f.place.id in
                              (select p.id from Place p where p.region.code = 'WEST')
                        """)
                .setParameter("t", PlaceNameNormalizer.jejuDayToUtc(출발일.plusDays(1)))
                .executeUpdate();

        SampleCourseGenerator.RunSummary summary = generator.generate(출발일);

        // 4번째 후보(북부)는 생존 불가라 재선발할 곳이 없다 - 2장으로 정직하게 끝난다
        assertThat(summary.readyRegions()).containsExactly("SOUTH", "EAST");
        assertThat(summary.failedRegions()).containsExactly("WEST");

        Course 서부 = courseByTitle("서부 고요 1박 2일", CourseStatus.FAILED);
        assertThat(서부.getGenerationErrorCode()).isEqualTo("NOT_ENOUGH_CANDIDATES");
        assertThat(itemRepository.findItemsWithPlace(서부.getId())).isEmpty();   // 1일차 고아 정리 확인
    }

    @Test
    void 오늘_실패한_프리셋은_직전_성공_코스가_카드로_남는다() {
        generator.generate(출발일);   // 3장 전부 성공

        // 다음 날 배치에서 서부만 실패하도록 - 새 출발일의 2일차 예보 제거
        em.createQuery("""
                        delete from CongestionForecast f
                        where f.forecastAt = :t and f.place.id in
                              (select p.id from Place p where p.region.code = 'WEST')
                        """)
                .setParameter("t", PlaceNameNormalizer.jejuDayToUtc(출발일.plusDays(2)))
                .executeUpdate();
        SampleCourseGenerator.RunSummary 다음날 = generator.generate(출발일.plusDays(1));

        assertThat(다음날.failedRegions()).containsExactly("WEST");
        List<CourseSummaryResponse> cards = mainCourseService.mainCourses();
        assertThat(cards).hasSize(3);   // 서부 자리는 어제 코스가 지킨다
        CourseSummaryResponse 서부카드 = cards.stream()
                .filter(c -> c.title().equals("서부 고요 1박 2일")).findFirst().orElseThrow();
        assertThat(서부카드.startDate()).isEqualTo(출발일);   // 직전 성공분
    }

    @Test
    void 같은_출발일은_재실행해도_다시_만들지_않는다() {
        generator.generate(출발일);
        long 첫배치_코스수 = courseRepository.count();

        SampleCourseGenerator.RunSummary 재실행 = generator.generate(출발일);

        assertThat(재실행.readyRegions()).hasSize(3);           // 스킵도 성공으로 집계
        assertThat(courseRepository.count()).isEqualTo(첫배치_코스수);   // 행이 쌓이지 않는다
    }

    @Test
    void 메인_카드는_한산한_순서로_세_장이_나온다() throws Exception {
        generator.generate(출발일);

        // 프리셋 id 순서는 서부→동부→남부(역순 시드)라, 아래 순서는 정렬이 실제로 동작해야만 나온다
        mockMvc.perform(get("/main/courses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(2000))
                .andExpect(jsonPath("$.result.length()").value(3))
                .andExpect(jsonPath("$.result[0].title").value("남부 여유 2박 3일"))
                .andExpect(jsonPath("$.result[0].duration_text").value("2박 3일"))
                .andExpect(jsonPath("$.result[0].congestion_level").value("QUIET"))
                .andExpect(jsonPath("$.result[0].congestion_label").value("여유"))
                .andExpect(jsonPath("$.result[0].people").value(2))
                .andExpect(jsonPath("$.result[0].place_count").value(9))
                .andExpect(jsonPath("$.result[0].estimated_cost_min").isEmpty())
                .andExpect(jsonPath("$.result[1].title").value("동부 한산 2박 3일"))
                .andExpect(jsonPath("$.result[2].title").value("서부 고요 1박 2일"))
                .andExpect(jsonPath("$.result[2].duration_text").value("1박 2일"));
    }

    @Test
    void 출발일이_지난_코스는_카드에서_빠진다() {
        CoursePreset preset = presetRepository.findByCode("SAMPLE_WEST").orElseThrow();
        Course 낡은코스 = courseRepository.save(Course.builder()
                .preset(preset).courseType(CourseType.SAMPLE)
                .title(preset.getDefaultTitle())
                .startDate(LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1))
                .endDate(LocalDate.now(ZoneId.of("Asia/Seoul")))
                .transport(Transport.RENTAL_CAR)
                .build());
        낡은코스.markReady();
        em.flush();

        assertThat(mainCourseService.mainCourses()).isEmpty();   // 낡은 추천을 오늘 것처럼 팔지 않는다
    }

    @Test
    void 배치가_아직_안_돌았으면_빈_목록이다() throws Exception {
        mockMvc.perform(get("/main/courses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.length()").value(0));
    }

    /** 예보가 아예 없는 날짜는 스킵 - 어제 코스가 남는 실패 내성의 전제. */
    @Test
    void 예보_없는_날짜는_생성하지_않고_스킵한다() {
        SampleCourseGenerator.RunSummary summary = generator.generate(LocalDate.of(2030, 1, 1));

        assertThat(summary.skippedReason()).isEqualTo("NO_FORECAST");
        assertThat(courseRepository.count()).isZero();
    }

    private Course courseByTitle(String title) {
        return courseByTitle(title, CourseStatus.READY);
    }

    private Course courseByTitle(String title, CourseStatus status) {
        return courseRepository.findAll().stream()
                .filter(c -> title.equals(c.getTitle()) && c.getStatus() == status)
                .findFirst().orElseThrow();
    }
}
