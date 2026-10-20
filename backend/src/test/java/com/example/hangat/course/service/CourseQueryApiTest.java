package com.example.hangat.course.service;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.course.model.CourseDetailResponse;
import com.example.hangat.course.model.CourseSummaryResponse;
import com.example.hangat.course.model.entity.Course;
import com.example.hangat.course.model.entity.CourseItem;
import com.example.hangat.course.model.enums.CourseType;
import com.example.hangat.course.model.enums.Transport;
import com.example.hangat.course.repository.CourseItemRepository;
import com.example.hangat.course.repository.CourseRepository;
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
import com.example.hangat.user.model.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 코스 조회·관리 API 검증 (상세 / 저장 목록 / 이름변경 / 삭제).
 *
 * <p>인증이 걸린 경로는 서비스를 직접 불러 검증한다 - 컨트롤러는 CurrentUser로 id를 꺼내
 * 그대로 넘기는 얇은 층이라, MockMvc로 SecurityContext를 꾸미는 것보다 권한 규칙 자체를
 * 찌르는 편이 정확하다. 공개 경로(상세)와 로그인 요구 응답은 MockMvc로 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CourseQueryApiTest {

    private static final LocalDate 출발일 = LocalDate.of(2026, 9, 20);
    private static final LocalDateTime 발표버전 = LocalDateTime.of(2026, 9, 19, 0, 0);

    @Autowired MockMvc mockMvc;
    @Autowired EntityManager em;
    @Autowired CourseQueryService queryService;
    @Autowired CourseCommandService commandService;
    @Autowired CourseRepository courseRepository;
    @Autowired CourseItemRepository itemRepository;
    @Autowired com.example.hangat.course.CourseBudgetService budgets;
    @Autowired com.example.hangat.course.repository.CourseItemCostRepository costs;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;
    @Autowired PlaceRepository placeRepository;
    @Autowired CongestionForecastRepository forecastRepository;
    @Autowired RegionRepository regionRepository;
    @Autowired PlaceCategoryRepository categoryRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.example.hangat.course.DbCourseWeatherFactsProvider weatherProvider;

    private PlaceCategory tourist;
    private DataSource source;
    private Region east;

    private User 주인;
    private User 남;
    private Course 임시코스;      // 소유자 없음 - 공개
    private Course 저장코스;      // 주인 소유

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "대표메뉴: 국수 0원", "대표메뉴: 국수 -5000원", "대표메뉴: 국수 8,00원",
            "대표메뉴: 국수 999999999999999999999원", "대표메뉴: 국수 2147483647원",
            "대표메뉴: 국수 8,000원 · 가격 문의", "입장료는 대략 5000원입니다"
    })
    void untrustedOrUnusableMenuPricesStayUnknown(String overview) {
        itemRepository.findItemsWithPlace(저장코스.getId()).get(0).getPlace().markGoodPrice(출발일, overview, null);
        var detail = mapper.valueToTree(queryService.detail(저장코스.getId(), 주인.getId()));
        assertThat(detail.path("budget_summary").path("has_cost_data").asBoolean()).isFalse();
        assertThat(detail.path("budget_summary").path("total_expected_max").isNull()).isTrue();
        assertThat(detail.path("budget_summary").has("budget_total")).isFalse();
    }

    @Test
    void legacySavedCourseReturnsBudgetAndEvidenceBasedEstimateWithoutWritingOnRead() throws Exception {
        var items = itemRepository.findItemsWithPlace(저장코스.getId());
        items.get(0).getPlace().markGoodPrice(출발일, "대표메뉴: 국수 8,000원 · 정식 12,000원", null);
        em.flush();
        long before = costs.count();
        var detail = mapper.valueToTree(queryService.detail(저장코스.getId(), 주인.getId()));
        assertThat(detail.has("budget_total")).isFalse();
        assertThat(detail.path("budget_summary").path("estimated_min").asInt()).isEqualTo(16000);
        assertThat(detail.path("budget_summary").path("estimated_max").asInt()).isEqualTo(24000);
        assertThat(detail.path("budget_summary").path("verified_total").asInt()).isZero();
        assertThat(detail.path("days").get(0).path("items").get(0).path("costs").get(0).path("accuracy_type").asText()).isEqualTo("ESTIMATED");
        var card = mapper.valueToTree(queryService.savedCourses(주인.getId(), PageRequest.of(0, 10)).getContent().get(0));
        assertThat(card.has("budget_total")).isFalse();
        assertThat(costs.count()).isEqualTo(before);
    }

    @Test
    void costCalculationPersistsMenuEstimateOnceAndKeepsUnknownPlacesUnknown() throws Exception {
        var items = itemRepository.findItemsWithPlace(임시코스.getId());
        items.get(0).getPlace().markGoodPrice(출발일, "대표메뉴: 국수 8,000원", null);
        var result = budgets.calculateAndCache(임시코스.getId());
        assertThat(result.totalExpectedMax()).isEqualTo(16000);
        assertThat(result.summary().unknownCount()).isEqualTo(2);
        budgets.calculateAndCache(임시코스.getId());
        assertThat(costs.findByCourseId(임시코스.getId())).hasSize(3);
        mockMvc.perform(get("/courses/{id}", 임시코스.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.budget_summary.budget_total").doesNotExist())
                .andExpect(jsonPath("$.result.budget_summary.estimated_max").value(16000))
                .andExpect(jsonPath("$.result.budget_summary.unknown_count").value(2));
    }

    @BeforeEach
    void seed() {
        org.mockito.Mockito.when(weatherProvider.loadDates(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anySet()))
                .thenReturn(com.example.hangat.course.weather.CourseWeatherFacts.empty());
        forecastRepository.deleteAll();

        east = regionRepository.findByCode("EAST")
                .orElseGet(() -> regionRepository.save(Region.builder()
                        .code("EAST").name("동부").displayOrder((byte) 3).build()));
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

        주인 = 회원가입("owner@hangat.local");
        남 = 회원가입("stranger@hangat.local");

        Place 성산 = place("성산일출봉", 33.4581, 126.9425, "35.00");
        Place 혼인지 = place("혼인지", 33.4335, 126.8930, "23.00");
        Place 표선 = place("표선해수욕장", 33.3260, 126.8370, "41.00");

        임시코스 = course(null, null);
        item(임시코스, 성산, 1, 1, 출발일, "80.00");    // 스냅숏 80 vs 지금 35 - 예보가 내려간 상태
        item(임시코스, 혼인지, 1, 2, 출발일, null);
        item(임시코스, 표선, 2, 1, 출발일.plusDays(1), null);
        임시코스.markReady();

        저장코스 = course(null, null);
        item(저장코스, 혼인지, 1, 1, 출발일, null);
        저장코스.markReady();
        저장코스.markSaved(주인, "동부 한산 코스");

        em.flush();
    }

    @Test
    void detailReadsCurrentWeatherByStoredDatesAndRegionWithoutChangingSchedule() throws Exception {
        var evidence = new com.example.hangat.course.facts.DailyWeatherEvidence("KMA_SHORT", "EAST", "REGION",
                "DAILY", 발표버전, new BigDecimal("20"), new BigDecimal("27"));
        var fact = new com.example.hangat.course.facts.WeatherFact(1L, 출발일, null, null, 30,
                "NONE", "흐림", null, null, evidence);
        var set = new com.example.hangat.course.facts.WeatherFactSet("east", "KMA", 57, 37,
                출발일, java.time.LocalTime.MIDNIGHT, List.of(fact));
        org.mockito.Mockito.when(weatherProvider.loadDates(임시코스.getStartDate(), 임시코스.getEndDate(), java.util.Set.of("EAST")))
                .thenReturn(new com.example.hangat.course.weather.CourseWeatherFacts(java.util.Map.of(), List.of(set)));
        mockMvc.perform(get("/courses/{id}", 임시코스.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.days[0].items[0].weather[0].precipitation_probability").value(30))
                .andExpect(jsonPath("$.result.days[0].items[0].weather[0].daily_evidence.region_code").value("EAST"))
                .andExpect(jsonPath("$.result.days[1].items[0].weather").isEmpty());
        var before = queryService.detail(임시코스.getId(), null);
        org.mockito.Mockito.when(weatherProvider.loadDates(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anySet()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("unavailable"));
        var after = queryService.detail(임시코스.getId(), null);
        assertThat(after.days().get(0).items().get(0).weather()).isEmpty();
        assertThat(after.days().get(0).items().get(0).id()).isEqualTo(before.days().get(0).items().get(0).id());
        assertThat(after.days().get(0).items().get(0).startTime()).isEqualTo(before.days().get(0).items().get(0).startTime());
        assertThat(after.accommodation()).isEqualTo(before.accommodation());
        mockMvc.perform(get("/courses/{id}", 임시코스.getId())).andExpect(status().isOk());
    }

    private User 회원가입(String email) {
        User user = User.signUpWithEmail(email, "bcrypt-해시-자리", "조회테스터-" + email.charAt(0));
        em.persist(user);
        return user;
    }

    private Place place(String name, double lat, double lng, String rate) {
        Place place = placeRepository.save(Place.builder()
                .name(name).normalizedName(name)
                .region(east).primaryCategory(tourist)
                .latitude(BigDecimal.valueOf(lat)).longitude(BigDecimal.valueOf(lng))
                .isGoodPrice(false).isHiddenGem(false).reviewCount(0)
                .build());
        for (int day = 0; day < 2; day++) {
            forecastRepository.save(CongestionForecast.of(place, source,
                    PlaceNameNormalizer.jejuDayToUtc(출발일.plusDays(day)), 발표버전,
                    new BigDecimal(rate)));
        }
        return place;
    }

    private Course course(User owner, String title) {
        return courseRepository.save(Course.builder()
                .user(owner).title(title)
                .people((short) 2)
                .startDate(출발일).endDate(출발일.plusDays(1))
                .transport(Transport.RENTAL_CAR)
                .averageCongestionRate(new BigDecimal("33.00"))
                .build());
    }

    /** plannedRate가 있으면 그 값으로 별도 스냅숏 예보를 만들어 붙인다(과거 발표 버전 흉내). */
    private CourseItem item(Course course, Place place, int dayNo, int position,
                            LocalDate date, String plannedRate) {
        CongestionForecast snapshot = null;
        if (plannedRate != null) {
            snapshot = forecastRepository.save(CongestionForecast.of(place, source,
                    PlaceNameNormalizer.jejuDayToUtc(date), 발표버전.minusDays(3),
                    new BigDecimal(plannedRate)));
        }
        return itemRepository.save(CourseItem.builder()
                .course(course).place(place)
                .dayNo((short) dayNo).position((short) position)
                .visitDate(date)
                .plannedCongestionForecast(snapshot)
                .recommendationReason("한산해요")
                .build());
    }

    @Test
    void 상세는_일차별로_묶여_나오고_혼잡을_두_값으로_준다() {
        CourseDetailResponse detail = queryService.detail(임시코스.getId(), null);

        assertThat(detail.days()).hasSize(2);
        assertThat(detail.days().get(0).dayNo()).isEqualTo(1);
        assertThat(detail.days().get(0).items()).hasSize(2);
        assertThat(detail.days().get(1).items()).hasSize(1);
        assertThat(detail.durationText()).isEqualTo("1박 2일");

        CourseDetailResponse.ItemDto 성산 = detail.days().get(0).items().get(0);
        assertThat(성산.placeName()).isEqualTo("성산일출봉");
        assertThat(성산.congestionRate()).isEqualTo(35.0);          // 지금 예보
        assertThat(성산.congestionLabel()).isEqualTo("한산");
        assertThat(성산.plannedCongestionRate()).isEqualTo(80.0);   // 저장 시점 스냅숏
        assertThat(성산.regionName()).isEqualTo("동부");
        assertThat(성산.latitude()).isNotNull();
    }

    @Test
    void 소유자_없는_임시_코스는_비로그인도_열_수_있다() throws Exception {
        mockMvc.perform(get("/courses/{id}", 임시코스.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(2000))
                .andExpect(jsonPath("$.result.days.length()").value(2))
                .andExpect(jsonPath("$.result.days[0].items[0].place_name").value("성산일출봉"))
                .andExpect(jsonPath("$.result.days[0].items[0].planned_congestion_rate").value(80.0))
                .andExpect(jsonPath("$.result.swappable").value(true))
                .andExpect(jsonPath("$.result.duration_text").value("1박 2일"));
    }

    @Test
    void 남의_저장_코스는_열_수_없다() {
        assertThatThrownBy(() -> queryService.detail(저장코스.getId(), 남.getId()))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("본인의 코스만");
        assertThatThrownBy(() -> queryService.detail(저장코스.getId(), null))
                .isInstanceOf(BaseException.class);

        CourseDetailResponse mine = queryService.detail(저장코스.getId(), 주인.getId());
        assertThat(mine.title()).isEqualTo("동부 한산 코스");
        assertThat(mine.manageable()).isTrue();
    }

    @Test
    void 샘플_코스는_공개지만_편집할_수_없다() {
        Course 샘플 = courseRepository.save(Course.builder()
                .courseType(CourseType.SAMPLE).title("동부 샘플")
                .startDate(출발일).endDate(출발일.plusDays(1))
                .transport(Transport.RENTAL_CAR)
                .build());
        샘플.markReady();
        em.flush();

        CourseDetailResponse detail = queryService.detail(샘플.getId(), 주인.getId());
        assertThat(detail.swappable()).isFalse();   // 공유 자산이라 남이 못 바꾼다
        assertThat(detail.manageable()).isFalse();
    }

    @Test
    void 삭제된_코스는_존재_여부까지_숨긴다() throws Exception {
        commandService.delete(저장코스.getId(), 주인.getId());
        em.flush();

        assertThatThrownBy(() -> queryService.detail(저장코스.getId(), 주인.getId()))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("존재하지 않는 코스");
        mockMvc.perform(get("/courses/{id}", 저장코스.getId()))
                .andExpect(jsonPath("$.code").value(3301));
    }

    @Test
    void 저장_목록은_내_SAVED_코스만_준다() {
        List<CourseSummaryResponse> mine = queryService
                .savedCourses(주인.getId(), PageRequest.of(0, 10)).getContent();

        assertThat(mine).hasSize(1);
        CourseSummaryResponse card = mine.get(0);
        assertThat(card.title()).isEqualTo("동부 한산 코스");
        assertThat(card.placeCount()).isEqualTo(1);
        assertThat(card.highlightNames()).containsExactly("혼인지");
        assertThat(card.regionName()).isEqualTo("동부");
        assertThat(card.durationText()).isEqualTo("1박 2일");
        assertThat(card.savedAt()).isNotNull();
        assertThat(card.congestionLabel()).isEqualTo("한산");

        // 임시 코스(미저장)와 남의 목록에는 안 잡힌다
        assertThat(mine).noneMatch(c -> c.id().equals(임시코스.getId()));
        assertThat(queryService.savedCourses(남.getId(), PageRequest.of(0, 10)).getContent())
                .isEmpty();
    }

    @Test
    void 삭제하면_목록에서_사라지고_다시_지워도_성공한다() {
        commandService.delete(저장코스.getId(), 주인.getId());
        em.flush();

        assertThat(queryService.savedCourses(주인.getId(), PageRequest.of(0, 10)).getContent())
                .isEmpty();
        commandService.delete(저장코스.getId(), 주인.getId());   // 멱등 - 두 번 눌러도 오류 없음
        assertThat(courseRepository.findById(저장코스.getId()).orElseThrow().getDeletedAt())
                .isNotNull();
    }

    @Test
    void 이름은_본인만_바꿀_수_있다() {
        CourseSummaryResponse renamed = commandService
                .rename(저장코스.getId(), "  가을 동부 여행  ", 주인.getId());
        assertThat(renamed.title()).isEqualTo("가을 동부 여행");   // 앞뒤 공백 정리

        assertThatThrownBy(() -> commandService.rename(저장코스.getId(), "가로채기", 남.getId()))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("본인의 코스만");
        // 소유자 없는 임시 코스는 이름 변경 대상이 아니다
        assertThatThrownBy(() -> commandService.rename(임시코스.getId(), "이름", 주인.getId()))
                .isInstanceOf(BaseException.class);
    }

    /**
     * 목록은 공개 목록에 없으므로 Security가 컨트롤러 앞에서 막는다(401 + 3002).
     * 컨트롤러의 LOGIN_REQUIRED 가드는 경로가 실수로 permitAll에 들어갔을 때를 위한 이중 방어다.
     */
    @Test
    void 남의_코스는_지울_수_없고_삭제된_코스는_남에게_없는_코스로_보인다() {
        assertThatThrownBy(() -> commandService.delete(저장코스.getId(), 남.getId()))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("본인의 코스만");
        assertThat(courseRepository.findById(저장코스.getId()).orElseThrow().getDeletedAt())
                .isNull();   // 남이 눌러도 지워지지 않는다

        commandService.delete(저장코스.getId(), 주인.getId());
        em.flush();

        // 삭제 뒤에는 남에게 "없는 코스"여야 한다 - 성공(2000)이 나가면 '존재했고 지워졌다'가 샌다
        assertThatThrownBy(() -> commandService.delete(저장코스.getId(), 남.getId()))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("존재하지 않는 코스");
        // 주인의 재삭제는 여전히 멱등
        commandService.delete(저장코스.getId(), 주인.getId());
    }

    @Test
    void 목록은_최근_저장순으로_페이징되고_카드마다_제_일정만_담는다() {
        Place 협재 = place("협재해수욕장", 33.3940, 126.2400, "18.00");
        Course 둘째 = course(null, null);
        item(둘째, 협재, 1, 1, 출발일, null);
        item(둘째, 협재, 1, 2, 출발일, null);   // 같은 장소 두 칸 - 개수 세기 확인용
        둘째.markReady();
        둘째.markSaved(주인, "서부 코스");
        em.flush();
        em.clear();

        var page = queryService.savedCourses(주인.getId(), PageRequest.of(0, 10));
        assertThat(page.getContent()).hasSize(2);
        // 최근 저장이 먼저 - @PageableDefault가 아닌 서비스 계약으로도 정렬이 보장돼야 한다
        assertThat(page.getContent().get(0).title()).isEqualTo("서부 코스");
        assertThat(page.getContent().get(1).title()).isEqualTo("동부 한산 코스");
        // 카드마다 자기 일정만 - findItemsOfCourses 그룹핑이 섞이면 여기서 깨진다
        assertThat(page.getContent().get(0).placeCount()).isEqualTo(2);
        assertThat(page.getContent().get(0).highlightNames()).containsExactly("협재해수욕장", "협재해수욕장");
        assertThat(page.getContent().get(1).placeCount()).isEqualTo(1);
        assertThat(page.getContent().get(1).highlightNames()).containsExactly("혼인지");

        // 2페이지 - 페이징 자체도 확인
        var second = queryService.savedCourses(주인.getId(), PageRequest.of(1, 1));
        assertThat(second.getContent()).hasSize(1);
        assertThat(second.getContent().get(0).title()).isEqualTo("동부 한산 코스");
    }

    @Test
    void 상세_평균은_지금_예보_기준이고_저장_시점_값도_함께_준다() {
        CourseDetailResponse detail = queryService.detail(임시코스.getId(), null);

        // 지금 예보 (35 + 23 + 41) / 3 = 33.00, 저장된 캐시는 생성 당시 값 33.00
        assertThat(detail.averageCongestionRate()).isEqualByComparingTo("33.00");
        assertThat(detail.congestionLabel()).isEqualTo("한산");
        assertThat(detail.plannedAverageCongestionRate()).isEqualByComparingTo("33.00");
        // 임시 코스는 스왑은 되지만 이름변경·삭제 대상이 아니다
        assertThat(detail.swappable()).isTrue();
        assertThat(detail.manageable()).isFalse();

        CourseDetailResponse saved = queryService.detail(저장코스.getId(), 주인.getId());
        assertThat(saved.swappable()).isTrue();
        assertThat(saved.manageable()).isTrue();
    }

    @Test
    void 저장_목록은_로그인이_필요하다() throws Exception {
        mockMvc.perform(get("/courses"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }
}
