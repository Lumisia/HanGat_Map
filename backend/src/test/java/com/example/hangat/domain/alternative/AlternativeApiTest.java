package com.example.hangat.domain.alternative;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 대안 장소 API(#과밀지역우회) 검증.
 * 기준: 성산일출봉(집중률 83, 혼잡) - 실좌표를 써서 거리 컷까지 실세계 값으로 검증한다.
 * 응답은 코스 도메인과 같은 snake_case 계약(프론트 AlternativePlace 타입).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AlternativeApiTest {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 1);
    private static final LocalDateTime 발표버전 = LocalDateTime.of(2026, 8, 29, 0, 0);

    @Autowired MockMvc mockMvc;
    @Autowired EntityManager em;
    @Autowired PlaceRepository placeRepository;
    @Autowired CongestionForecastRepository forecastRepository;
    @Autowired RegionRepository regionRepository;
    @Autowired PlaceCategoryRepository categoryRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.example.hangat.course.route.AlternativeRoadDistance roads;

    private Region east;
    private PlaceCategory tourist;
    private PlaceCategory cafe;
    private DataSource source;

    private Long baseId;        // 성산일출봉 (혼잡)
    private Long nearCalmId;    // 혼인지 (~5.3km, 여유)

    @BeforeEach
    void seed() {
        forecastRepository.deleteAll();

        east = regionRepository.findByCode("EAST")
                .orElseGet(() -> regionRepository.save(Region.builder()
                        .code("EAST").name("동부").displayOrder((byte) 3).build()));
        tourist = categoryRepository.findByCode("TOURIST")
                .orElseGet(() -> categoryRepository.save(PlaceCategory.builder()
                        .code("TOURIST").name("관광지").build()));
        cafe = categoryRepository.findByCode("CAFE")
                .orElseGet(() -> categoryRepository.save(PlaceCategory.builder()
                        .code("CAFE").name("카페").build()));
        source = em.find(DataSource.class, "KTO_CNCTR");
        if (source == null) {
            source = DataSource.builder()
                    .code("KTO_CNCTR").displayName("한국관광공사 관광지별 집중률")
                    .providerName("한국관광공사").attributionText("한국관광공사")
                    .displayOrder((short) 2).isActive(true)
                    .build();
            em.persist(source);
        }

        baseId = save("성산일출봉", tourist, 33.4581, 126.9425, "83.00");
        nearCalmId = save("혼인지", tourist, 33.4335, 126.8930, "23.00");    // ~5.3km 여유
        save("광치기해변", tourist, 33.4515, 126.9246, "45.00");             // ~1.8km 보통
        save("표선해수욕장", tourist, 33.4581, 126.9425, "78.00");           // 근처지만 혼잡 - 컷
        save("협재해수욕장", tourist, 33.3940, 126.2400, "10.00");           // ~65km - 거리 컷
        save("성산카페", cafe, 33.4500, 126.9300, "5.00");                   // 카테고리 다름 - 컷
        em.flush();
    }

    private Long save(String name, PlaceCategory category, double lat, double lng, String rate) {
        Place place = placeRepository.save(Place.builder()
                .name(name).normalizedName(name)
                .region(east).primaryCategory(category)
                .latitude(BigDecimal.valueOf(lat))
                .longitude(BigDecimal.valueOf(lng))
                .isGoodPrice(false).isHiddenGem(false).reviewCount(0)
                .build());
        forecastRepository.save(CongestionForecast.of(
                place, source, PlaceNameNormalizer.jejuDayToUtc(DATE), 발표버전, new BigDecimal(rate)));
        return place.getId();
    }

    @Test
    void 같은_카테고리_반경내_저혼잡만_집중률_오름차순으로_준다() throws Exception {
        mockMvc.perform(get("/places/{id}/alternatives", baseId).param("date", DATE.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(2000))
                .andExpect(jsonPath("$.result.length()").value(2))   // 혼인지 + 광치기 (혼잡·원거리·카페 컷)
                .andExpect(jsonPath("$.result[0].place_name").value("혼인지"))
                .andExpect(jsonPath("$.result[0].congestion_level").value("QUIET"))
                .andExpect(jsonPath("$.result[0].congestion_label").value("한산"))
                .andExpect(jsonPath("$.result[0].distance_m").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.greaterThan(5000),
                        org.hamcrest.Matchers.lessThan(5700))))
                .andExpect(jsonPath("$.result[0].radius_km").value(10))
                .andExpect(jsonPath("$.result[0].category_name").value("관광지"))
                // 교체 설득 문구는 기준 장소 대비 격차를 말한다 (83 - 23 = 60)
                .andExpect(jsonPath("$.result[0].replacement_reason")
                        .value(org.hamcrest.Matchers.containsString("60 낮")))
                .andExpect(jsonPath("$.result[1].place_name").value("광치기해변"))
                .andExpect(jsonPath("$.result[?(@.place_name == '협재해수욕장')]").isEmpty())
                .andExpect(jsonPath("$.result[?(@.place_name == '표선해수욕장')]").isEmpty())
                .andExpect(jsonPath("$.result[?(@.place_name == '성산카페')]").isEmpty());
    }

    @Test
    void 가까운_후보가_충분하면_먼_후보는_섞이지_않는다() throws Exception {
        // 15km 지점에 가장 한산한(1.00) 후보를 둬도, 10km 안이 정원을 채우면 밀린다
        save("먼오름", tourist, 33.5900, 126.9800, "1.00");   // 성산에서 약 15km

        mockMvc.perform(get("/places/{id}/alternatives", baseId)
                        .param("date", DATE.toString())
                        .param("limit", "2"))
                .andExpect(jsonPath("$.result.length()").value(2))
                .andExpect(jsonPath("$.result[*].radius_km",
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(10))))
                .andExpect(jsonPath("$.result[?(@.place_name == '먼오름')]").isEmpty());
    }

    @Test
    void 가까운_후보가_모자라면_먼_후보로_채운다() throws Exception {
        save("먼오름", tourist, 33.5900, 126.9800, "1.00");   // 약 15km

        mockMvc.perform(get("/places/{id}/alternatives", baseId)
                        .param("date", DATE.toString())
                        .param("limit", "5"))
                .andExpect(jsonPath("$.result.length()").value(3))
                // 집중률은 1.00으로 가장 낮지만 근거리를 먼저 채우므로 마지막에 온다
                .andExpect(jsonPath("$.result[2].place_name").value("먼오름"))
                .andExpect(jsonPath("$.result[2].radius_km").value(20));
    }

    @Test
    void exclude에_있는_장소는_대안에서_빠진다() throws Exception {
        mockMvc.perform(get("/places/{id}/alternatives", baseId)
                        .param("date", DATE.toString())
                        .param("exclude", nearCalmId.toString()))
                .andExpect(jsonPath("$.result[?(@.place_name == '혼인지')]").isEmpty())
                .andExpect(jsonPath("$.result[0].place_name").value("광치기해변"));
    }

    @Test
    void 없는_장소면_3201을_돌려준다() throws Exception {
        mockMvc.perform(get("/places/{id}/alternatives", 999999).param("date", DATE.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(3201));
    }

    @Test
    void 거리순은_집중률보다_거리를_우선하고_소개와_사진을_반환한다() throws Exception {
        Place near = placeRepository.findById(nearCalmId).orElseThrow();
        near.updateOverview("호수와 숲을 둘러보는 산책 장소입니다.");
        mockMvc.perform(get("/places/{id}/alternatives", baseId)
                        .param("date", DATE.toString()).param("sort", "DISTANCE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.length()").value(2))
                .andExpect(jsonPath("$.result[0].place_name").value("광치기해변"))
                .andExpect(jsonPath("$.result[1].place_name").value("혼인지"))
                .andExpect(jsonPath("$.result[1].overview").value("호수와 숲을 둘러보는 산책 장소입니다."));
    }

    @Test
    void 예보가_없는_날짜는_3401을_돌려준다() throws Exception {
        mockMvc.perform(get("/places/{id}/alternatives", baseId).param("date", "2030-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(3401));
    }

    @Test
    void 직선_대안은_오늘_예보로_전체_후보를_거리순으로_주고_도로_API를_호출하지_않는다() throws Exception {
        for (int i = 1; i <= 11; i++) save("추가 후보 " + i, tourist, 33.46 + i * 0.01, 126.9425, "20.00");
        LocalDate today = com.example.hangat.common.util.DateTimes.todayKst();
        for (var forecast : forecastRepository.findAll()) {
            forecastRepository.save(CongestionForecast.of(forecast.getPlace(), source,
                    PlaceNameNormalizer.jejuDayToUtc(today), 발표버전, forecast.getRate()));
        }
        em.flush();
        mockMvc.perform(get("/places/{id}/straight-alternatives", baseId).param("exclude", nearCalmId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.distance_basis").value("STRAIGHT_LINE"))
                .andExpect(jsonPath("$.result.forecast_date").value(today.toString()))
                .andExpect(jsonPath("$.result.places.length()").value(12))
                .andExpect(jsonPath("$.result.places[0].place_name").value("추가 후보 1"))
                .andExpect(jsonPath("$.result.places[1].place_name").value("광치기해변"))
                .andExpect(jsonPath("$.result.places[*].distance_m", org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.lessThanOrEqualTo(20000))))
                .andExpect(jsonPath("$.result.places[?(@.place_name == '혼인지')]").isEmpty());
        org.mockito.Mockito.verifyNoInteractions(roads);
    }

    @Test
    void 직선_대안은_20km_경계_밖을_제외하고_예보없음과_빈결과를_구분한다() throws Exception {
        forecastRepository.deleteAll();
        mockMvc.perform(get("/places/{id}/straight-alternatives", baseId))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(3401));
        Long inside = save("경계 안", tourist, 33.6378, 126.9425, "20.00"); // 약 19.98km
        save("경계 밖", tourist, 33.6381, 126.9425, "20.00"); // 약 20.02km
        LocalDate today = com.example.hangat.common.util.DateTimes.todayKst();
        for (var forecast : forecastRepository.findAll()) {
            forecastRepository.save(CongestionForecast.of(forecast.getPlace(), source,
                    PlaceNameNormalizer.jejuDayToUtc(today), 발표버전, forecast.getRate()));
        }
        em.flush();
        mockMvc.perform(get("/places/{id}/straight-alternatives", baseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.places.length()").value(1))
                .andExpect(jsonPath("$.result.places[0].place_id").value(inside));
        mockMvc.perform(get("/places/{id}/straight-alternatives", baseId).param("exclude", inside.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.places").isEmpty());
        org.mockito.Mockito.verifyNoInteractions(roads);
    }

    @Test
    void 자동차_대안은_오늘_예보와_DB_소개를_사용하고_제외목록을_반영한다() throws Exception {
        LocalDate today = com.example.hangat.common.util.DateTimes.todayKst();
        Place candidate = placeRepository.findById(nearCalmId).orElseThrow();
        candidate.updateOverview("호수와 숲을 둘러보는 산책 장소입니다.");
        forecastRepository.save(CongestionForecast.of(candidate, source,
                PlaceNameNormalizer.jejuDayToUtc(today), 발표버전, new BigDecimal("23.00")));
        em.flush();
        org.mockito.Mockito.when(roads.distance(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.OptionalInt.of(12345));
        mockMvc.perform(get("/places/{id}/road-alternatives",baseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.distance_basis").value("CAR_ROAD"))
                .andExpect(jsonPath("$.result.forecast_date").value(today.toString()))
                .andExpect(jsonPath("$.result.places[0].distance_m").value(12345))
                .andExpect(jsonPath("$.result.places[0].overview").value("호수와 숲을 둘러보는 산책 장소입니다."));
        mockMvc.perform(get("/places/{id}/road-alternatives",baseId).param("exclude",nearCalmId.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.places").isEmpty());
        org.mockito.Mockito.verify(roads,org.mockito.Mockito.times(1)).distance(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
    }
}
