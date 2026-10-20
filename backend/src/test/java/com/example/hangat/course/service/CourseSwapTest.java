package com.example.hangat.course.service;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.course.model.CourseSwapResponse;
import com.example.hangat.course.model.entity.Course;
import com.example.hangat.course.model.entity.CourseItem;
import com.example.hangat.course.model.enums.CourseItemSource;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 코스 스왑(#과밀지역우회) 검증.
 *
 * <p>시나리오: 1일차 [성산일출봉(83, 혼잡) → 광치기해변(45)], 2일차 [표선해수욕장(30)].
 * 1일차 첫 칸을 혼인지(23)로 바꾸면 순서는 그대로, 광치기해변의 이동거리는 바뀌어야 한다.
 * 실좌표라 거리 변화가 실세계 값으로 검증된다(성산→광치기 약 1.8km / 혼인지→광치기 약 3.5km).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CourseSwapTest {

    private static final LocalDate 출발일 = LocalDate.of(2026, 9, 10);
    private static final LocalDateTime 발표버전 = LocalDateTime.of(2026, 9, 9, 0, 0);

    @Autowired MockMvc mockMvc;
    @Autowired EntityManager em;
    @Autowired CourseSwapService swapService;
    @Autowired CourseRepository courseRepository;
    @Autowired CourseItemRepository itemRepository;
    @Autowired com.example.hangat.course.CourseBudgetService budgets;
    @Autowired com.example.hangat.course.repository.CourseItemCostRepository costs;

    @Test
    void swappingRemovesOldPlaceCostsAndRecalculatesNewMenuEstimate() throws Exception {
        성산일출봉.markGoodPrice(출발일, "대표메뉴: 정식 8,000원", null);
        혼인지.markGoodPrice(출발일, "대표메뉴: 정식 12,000원", null);
        budgets.calculateAndCache(course.getId());
        assertThat(course.getEstimatedCostMax()).isEqualTo(8000 * course.getPeople());
        var response = swapService.swap(course.getId(), 첫날_첫칸.getId(), 혼인지.getId(), null);
        assertThat(course.getEstimatedCostMax()).isEqualTo(12000 * course.getPeople());
        assertThat(costs.findByCourseId(course.getId()).stream()
                .filter(c -> c.getCourseItem() != null && c.getCourseItem().getId().equals(첫날_첫칸.getId()))
                .map(c -> c.getAmountMax())).containsExactly(12000 * course.getPeople());
        swapService.swap(course.getId(), 첫날_첫칸.getId(), 예보없는곳.getId(), null);
        assertThat(course.getEstimatedCostMax()).isNull();
    }
    @Autowired PlaceRepository placeRepository;
    @Autowired CongestionForecastRepository forecastRepository;
    @Autowired RegionRepository regionRepository;
    @Autowired PlaceCategoryRepository categoryRepository;

    private PlaceCategory tourist;
    private DataSource source;

    private Place 성산일출봉;
    private Place 광치기해변;
    private Place 혼인지;
    private Place 표선해수욕장;
    private Place 예보없는곳;

    private Course course;
    private CourseItem 첫날_첫칸;
    private CourseItem 첫날_둘째칸;

    @BeforeEach
    void seed() {
        forecastRepository.deleteAll();

        Region east = regionRepository.findByCode("EAST")
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

        성산일출봉 = place(east, "성산일출봉", 33.4581, 126.9425, "83.00");
        광치기해변 = place(east, "광치기해변", 33.4515, 126.9246, "45.00");
        혼인지 = place(east, "혼인지", 33.4335, 126.8930, "23.00");
        표선해수욕장 = place(east, "표선해수욕장", 33.3260, 126.8370, "30.00");
        예보없는곳 = place(east, "예보없는오름", 33.4400, 126.9000, null);   // 커버 밖

        course = courseRepository.save(Course.builder()
                .startDate(출발일).endDate(출발일.plusDays(1))
                .transport(Transport.RENTAL_CAR)
                .build());
        첫날_첫칸 = item(성산일출봉, 1, 1, 출발일, null);
        첫날_둘째칸 = item(광치기해변, 1, 2, 출발일, 성산일출봉);
        item(표선해수욕장, 2, 1, 출발일.plusDays(1), null);
        course.markReady();
        em.flush();
    }

    private Place place(Region region, String name, double lat, double lng, String rate) {
        Place place = placeRepository.save(Place.builder()
                .name(name).normalizedName(name)
                .region(region).primaryCategory(tourist)
                .latitude(BigDecimal.valueOf(lat)).longitude(BigDecimal.valueOf(lng))
                .isGoodPrice(false).isHiddenGem(false).reviewCount(0)
                .build());
        if (rate != null) {
            for (int day = 0; day < 2; day++) {
                forecastRepository.save(CongestionForecast.of(place, source,
                        PlaceNameNormalizer.jejuDayToUtc(출발일.plusDays(day)), 발표버전,
                        new BigDecimal(rate)));
            }
        }
        return place;
    }

    private CourseItem item(Place place, int dayNo, int position, LocalDate date, Place previous) {
        Integer distanceM = null;
        Short minutes = null;
        if (previous != null) {   // 시드에서는 대략치로 넣고, 스왑이 다시 계산하는지를 본다
            distanceM = 1820;
            minutes = (short) 3;
        }
        return itemRepository.save(CourseItem.builder()
                .course(course).place(place)
                .dayNo((short) dayNo).position((short) position)
                .visitDate(date)
                .inboundDistanceM(distanceM).inboundTravelMinutes(minutes)
                .recommendationReason("엔진이 고른 곳")
                .build());
    }

    @Test
    void 스왑하면_순서는_그대로고_장소와_흔적_스냅숏이_바뀐다() {
        CourseSwapResponse response = swapService.swap(
                course.getId(), 첫날_첫칸.getId(), 혼인지.getId(), null);
        em.flush();
        em.clear();

        CourseItem loaded = itemRepository.findById(첫날_첫칸.getId()).orElseThrow();
        assertThat(loaded.getPlace().getName()).isEqualTo("혼인지");
        assertThat(loaded.getReplacedFromPlace().getName()).isEqualTo("성산일출봉");
        assertThat(loaded.getItemSource()).isEqualTo(CourseItemSource.REPLACEMENT);
        assertThat(loaded.getRecommendationReason()).isEqualTo("직접 바꾼 곳이에요");
        assertThat(loaded.getPlannedCongestionForecast().getRate()).isEqualByComparingTo("23.00");
        // 순서 유지 - 지도 번호 마커가 흔들리면 안 된다
        assertThat(loaded.getDayNo()).isEqualTo((short) 1);
        assertThat(loaded.getPosition()).isEqualTo((short) 1);

        assertThat(response.message()).contains("성산일출봉").contains("혼인지");
    }

    @Test
    void 다음_일정의_이동거리가_함께_갱신된다() {
        swapService.swap(course.getId(), 첫날_첫칸.getId(), 혼인지.getId(), null);
        em.flush();
        em.clear();

        CourseItem 첫칸 = itemRepository.findById(첫날_첫칸.getId()).orElseThrow();
        CourseItem 둘째칸 = itemRepository.findById(첫날_둘째칸.getId()).orElseThrow();

        // 하루의 첫 일정은 전날과 잇지 않는다
        assertThat(첫칸.getInboundDistanceM()).isNull();
        assertThat(첫칸.getInboundTravelMinutes()).isNull();
        // 성산->광치기(약 1.8km)에서 혼인지->광치기(약 3.5km)로 바뀌어야 한다
        assertThat(둘째칸.getInboundDistanceM()).isBetween(3200, 3900);
        assertThat(둘째칸.getInboundTravelMinutes()).isPositive();
    }

    @Test
    void 응답은_교체된_일정과_다음_일정만_돌려준다() {
        CourseSwapResponse response = swapService.swap(
                course.getId(), 첫날_첫칸.getId(), 혼인지.getId(), null);

        assertThat(response.updatedItems()).hasSize(2);
        CourseSwapResponse.SwappedItem 교체됨 = response.updatedItems().get(0);
        assertThat(교체됨.placeName()).isEqualTo("혼인지");
        assertThat(교체됨.replacedFromPlaceName()).isEqualTo("성산일출봉");
        assertThat(교체됨.congestionLabel()).isEqualTo("한산");
        assertThat(교체됨.inboundDistanceM()).isNull();
        assertThat(response.updatedItems().get(1).placeName()).isEqualTo("광치기해변");
        // 2일차 일정은 영향이 없으므로 응답에 없다
        assertThat(response.updatedItems())
                .noneMatch(item -> item.placeName().equals("표선해수욕장"));
    }

    @Test
    void 코스_평균_집중률이_현재_예보로_재계산된다() {
        CourseSwapResponse response = swapService.swap(
                course.getId(), 첫날_첫칸.getId(), 혼인지.getId(), null);
        em.flush();

        // (23 + 45 + 30) / 3 = 32.67 - 스왑 전 (83+45+30)/3 = 52.67에서 내려간다
        assertThat(response.averageCongestionRate()).isEqualByComparingTo("32.67");
        assertThat(response.congestionLabel()).isEqualTo("한산");
        assertThat(courseRepository.findById(course.getId()).orElseThrow()
                .getAverageCongestionRate()).isEqualByComparingTo("32.67");
    }

    @Test
    void 예보가_없는_장소로_바꾸면_스냅숏은_비고_평균에서_빠진다() {
        CourseSwapResponse response = swapService.swap(
                course.getId(), 첫날_첫칸.getId(), 예보없는곳.getId(), null);
        em.flush();
        em.clear();

        assertThat(itemRepository.findById(첫날_첫칸.getId()).orElseThrow()
                .getPlannedCongestionForecast()).isNull();
        assertThat(response.updatedItems().get(0).congestionRate()).isNull();
        assertThat(response.updatedItems().get(0).congestionLabel()).isNull();
        // 남은 두 곳(45, 30)만 평균 - 정보 없음을 0으로 채우지 않는다
        assertThat(response.averageCongestionRate()).isEqualByComparingTo("37.50");
    }

    @Test
    void 이미_코스에_있는_장소로는_바꿀_수_없다() {
        assertThatThrownBy(() -> swapService.swap(
                course.getId(), 첫날_첫칸.getId(), 광치기해변.getId(), null))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("이미 코스에 담긴 장소");
    }

    @Test
    void 소유자가_있는_코스는_본인만_바꿀_수_있다() {
        User 주인 = 회원가입("owner@hangat.local");
        User 남 = 회원가입("stranger@hangat.local");
        course.markSaved(주인, "내 코스");
        em.flush();

        assertThatThrownBy(() -> swapService.swap(
                course.getId(), 첫날_첫칸.getId(), 혼인지.getId(), 남.getId()))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("본인의 코스만");
        // 비로그인도 마찬가지로 막힌다
        assertThatThrownBy(() -> swapService.swap(
                course.getId(), 첫날_첫칸.getId(), 혼인지.getId(), null))
                .isInstanceOf(BaseException.class);

        CourseSwapResponse response = swapService.swap(
                course.getId(), 첫날_첫칸.getId(), 혼인지.getId(), 주인.getId());
        assertThat(response.updatedItems().get(0).placeName()).isEqualTo("혼인지");
    }

    @Test
    void 비회원_코스는_로그인_없이_스왑된다() throws Exception {
        mockMvc.perform(post("/courses/{courseId}/items/{itemId}/swap",
                        course.getId(), 첫날_첫칸.getId())
                        .contentType(APPLICATION_JSON)
                        .content("{\"place_id\":" + 혼인지.getId() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(2000))
                .andExpect(jsonPath("$.result.updated_items[0].place_name").value("혼인지"))
                .andExpect(jsonPath("$.result.updated_items[0].replaced_from_place_name")
                        .value("성산일출봉"))
                .andExpect(jsonPath("$.result.congestion_label").value("한산"));
    }

    @Test
    void 없는_코스나_일정이면_거부한다() throws Exception {
        mockMvc.perform(post("/courses/{courseId}/items/{itemId}/swap", 999999, 첫날_첫칸.getId())
                        .contentType(APPLICATION_JSON)
                        .content("{\"place_id\":" + 혼인지.getId() + "}"))
                .andExpect(jsonPath("$.code").value(3301));

        mockMvc.perform(post("/courses/{courseId}/items/{itemId}/swap", course.getId(), 999999)
                        .contentType(APPLICATION_JSON)
                        .content("{\"place_id\":" + 혼인지.getId() + "}"))
                .andExpect(jsonPath("$.code").value(3306));
    }

    /**
     * 샘플 코스는 소유자가 없지만 "임자 없는 코스"가 아니라 모두의 것이다.
     * 소유자 검사만 있으면 통과해 버려, 메인 추천 카드를 누구나 영구히 바꿀 수 있게 된다.
     */
    @Test
    void 메인_샘플_코스는_아무도_스왑할_수_없다() {
        Course 샘플 = courseRepository.save(Course.builder()
                .courseType(com.example.hangat.course.model.enums.CourseType.SAMPLE)
                .title("동부 샘플")
                .startDate(출발일).endDate(출발일.plusDays(1))
                .transport(Transport.RENTAL_CAR)
                .build());
        CourseItem 샘플일정 = itemRepository.save(CourseItem.builder()
                .course(샘플).place(성산일출봉)
                .dayNo((short) 1).position((short) 1)
                .visitDate(출발일)
                .build());
        샘플.markReady();
        em.flush();

        assertThatThrownBy(() -> swapService.swap(
                샘플.getId(), 샘플일정.getId(), 혼인지.getId(), null))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("본인의 코스만");
        // 로그인 사용자도 마찬가지 - 공유 자산이라 주인이 없다
        assertThatThrownBy(() -> swapService.swap(
                샘플.getId(), 샘플일정.getId(), 혼인지.getId(), 회원가입("any@hangat.local").getId()))
                .isInstanceOf(BaseException.class);
        em.clear();
        assertThat(itemRepository.findById(샘플일정.getId()).orElseThrow().getPlace().getName())
                .isEqualTo("성산일출봉");   // 그대로여야 한다
    }

    @Test
    void 삭제된_코스는_스왑되지_않는다() {
        course.softDelete();
        em.flush();

        assertThatThrownBy(() -> swapService.swap(
                course.getId(), 첫날_첫칸.getId(), 혼인지.getId(), null))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("존재하지 않는 코스");
    }

    private User 회원가입(String email) {
        User user = User.signUpWithEmail(email, "bcrypt-해시-자리", "스왑테스터-" + email.charAt(0));
        em.persist(user);
        return user;
    }
}
