package com.example.hangat.map.service;

import com.example.hangat.map.model.dto.CrowdForecastResponse;
import com.example.hangat.map.model.entity.CongestionForecast;
import com.example.hangat.map.model.entity.DataSource;
import com.example.hangat.map.model.entity.Place;
import com.example.hangat.map.model.entity.PlaceCategory;
import com.example.hangat.map.model.entity.Region;
import com.example.hangat.map.repository.CongestionForecastRepository;
import com.example.hangat.map.repository.PlaceCategoryRepository;
import com.example.hangat.map.repository.RegionRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 혼잡 예보 조회 - 날짜 인덱스와 발표 버전 고정 검증.
 *
 * <p><b>첫 두 테스트가 이 커밋의 급소다.</b>
 * <ul>
 *   <li><b>날짜 인덱스</b>: 장소마다 예보가 있는 날이 달라서, 정렬 순서대로 배열에 밀어 넣으면
 *       중간에 하루 빠진 장소의 뒤쪽이 전부 하루씩 당겨진다. 값이 그럴듯해 화면에서는 못 잡고,
 *       '가장 한산한 날'만 엉뚱하게 나온다</li>
 *   <li><b>UTC 되돌리기</b>: 저장은 UTC, 응답은 제주 날짜다. 한 번 더 변환하거나 빼먹으면
 *       캘린더 전체가 하루 밀린다</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CrowdForecastServiceTest {

    private static final LocalDateTime 어제발표 = LocalDateTime.of(2026, 8, 23, 0, 0);
    private static final LocalDateTime 오늘발표 = LocalDateTime.of(2026, 8, 24, 0, 0);

    /** 제주 8/24 00:00 == UTC 8/23 15:00 */
    private static final LocalDate 첫날 = LocalDate.of(2026, 8, 24);

    @Autowired
    private EntityManager em;

    @Autowired
    private CrowdForecastService service;

    @Autowired
    private CongestionForecastRepository forecastRepository;

    @Autowired
    private RegionRepository regionRepository;

    @Autowired
    private PlaceCategoryRepository categoryRepository;

    private Place 금오름;
    private Place 성산일출봉;
    private DataSource 집중률;

    @BeforeEach
    void setUp() {
        forecastRepository.deleteAll();

        // ⚠️ 마스터를 '있으면 쓰고 없으면 만든다'로 짠 이유:
        // @SpringBootTest는 MapMasterDataInitializer를 돌려 권역 4·카테고리 7·출처 4를 채우지만,
        // 같은 H2를 쓰는 @DataJpaTest들이 ddl-auto=create-drop으로 스키마를 다시 만들면 그게 날아간다.
        // 그래서 이 테스트만 단독 실행하면 통과하고 전체 실행에서는 깨진다 - 실행 순서에 기대면 안 된다.
        // (새로 만들 때는 display_order가 regions에서 UNIQUE라는 점도 걸린다)
        Region west = regionRepository.findByCode("WEST")
                .orElseGet(() -> regionRepository.save(Region.builder()
                        .code("WEST").name("서부").displayOrder((byte) 2).build()));
        PlaceCategory tourist = categoryRepository.findByCode("TOURIST")
                .orElseGet(() -> categoryRepository.save(PlaceCategory.builder()
                        .code("TOURIST").name("관광지").build()));
        집중률 = em.find(DataSource.class, "KTO_CNCTR");
        if (집중률 == null) {
            집중률 = DataSource.builder()
                    .code("KTO_CNCTR").displayName("한국관광공사 관광지별 집중률")
                    .providerName("한국관광공사").attributionText("한국관광공사")
                    .displayOrder((short) 2).isActive(true)
                    .build();
            em.persist(집중률);
        }

        금오름 = persistPlace(west, tourist, "금오름");
        성산일출봉 = persistPlace(west, tourist, "성산일출봉");
        em.flush();
    }

    @Test
    void 중간에_빠진_날은_null이고_뒤쪽_값이_당겨지지_않는다() {
        // 금오름은 1일차·3일차만 있다. 2일차가 비어야 하고 3일차 값이 2번 자리로 오면 안 된다
        save(금오름, 오늘발표, 첫날, "10.00");
        save(금오름, 오늘발표, 첫날.plusDays(2), "30.00");
        save(성산일출봉, 오늘발표, 첫날.plusDays(1), "50.00");
        em.flush();
        em.clear();

        CrowdForecastResponse res = service.getForecast();

        assertThat(res.getBaseDate()).isEqualTo(오늘발표.toLocalDate());
        assertThat(res.getFrom()).isEqualTo(첫날);
        assertThat(res.getDays()).isEqualTo(3);

        List<BigDecimal> 금오름시리즈 = res.getValues().get(String.valueOf(금오름.getId()));
        assertThat(금오름시리즈).hasSize(3);
        assertThat(금오름시리즈.get(0)).isEqualByComparingTo("10.00");
        assertThat(금오름시리즈.get(1)).isNull();          // ★ 빠진 날
        assertThat(금오름시리즈.get(2)).isEqualByComparingTo("30.00");

        // 성산일출봉은 2일차에만 있다 - 배열 길이는 같고 앞뒤가 null
        List<BigDecimal> 성산시리즈 = res.getValues().get(String.valueOf(성산일출봉.getId()));
        assertThat(성산시리즈).containsExactly(null, new BigDecimal("50.00"), null);
    }

    @Test
    void 응답_날짜는_UTC가_아니라_제주_날짜다() {
        save(금오름, 오늘발표, 첫날, "10.00");
        em.flush();
        em.clear();

        // DB에는 UTC 8/23 15:00으로 들어가 있다
        assertThat(forecastRepository.findAll().get(0).getForecastAt())
                .isEqualTo(LocalDateTime.of(2026, 8, 23, 15, 0));
        // 응답은 제주 8/24로 되돌아와야 한다
        assertThat(service.getForecast().getFrom()).isEqualTo(LocalDate.of(2026, 8, 24));
    }

    @Test
    void 최신_발표_버전만_내려간다() {
        save(금오름, 어제발표, 첫날, "55.00");
        save(금오름, 오늘발표, 첫날, "22.00");
        em.flush();
        em.clear();

        CrowdForecastResponse res = service.getForecast();

        // 한 장소·한 날짜에 값은 하나여야 한다
        List<BigDecimal> series = res.getValues().get(String.valueOf(금오름.getId()));
        assertThat(series).hasSize(1);
        assertThat(series.get(0)).isEqualByComparingTo("22.00");   // 어제 것(55)이 아니다
    }

    @Test
    void 예보가_없으면_빈_응답이지_오류가_아니다() {
        CrowdForecastResponse res = service.getForecast();

        assertThat(res.getBaseDate()).isNull();
        assertThat(res.getFrom()).isNull();
        assertThat(res.getDays()).isZero();
        assertThat(res.getValues()).isEmpty();
    }

    @Test
    void 발표일은_창_시작일과_별개다_새벽_적재는_어제부터_시작하는_창을_받는다() {
        // 관광공사 API는 새벽 3시에 아직 어제부터 시작하는 창을 준다(2026-09 실측).
        // 창 시작일로 '묵은 예보'를 판정하면 정상 적재에도 매일 갱신 대기가 뜬다 - 화면은 발표일을 봐야 한다.
        save(금오름, 오늘발표, 첫날.minusDays(1), "40.00");
        save(금오름, 오늘발표, 첫날, "20.00");
        em.flush();
        em.clear();

        CrowdForecastResponse res = service.getForecast();

        assertThat(res.getBaseDate()).isEqualTo(LocalDate.of(2026, 8, 24));
        assertThat(res.getFrom()).isEqualTo(LocalDate.of(2026, 8, 23));   // 창은 하루 앞에서 시작한다
        assertThat(res.getBaseDate()).isAfter(res.getFrom());
    }

    private void save(Place place, LocalDateTime baseAt, LocalDate jejuDay, String rate) {
        forecastRepository.save(CongestionForecast.of(
                place, 집중률, PlaceNameNormalizer.jejuDayToUtc(jejuDay), baseAt, new BigDecimal(rate)));
    }

    private Place persistPlace(Region region, PlaceCategory category, String name) {
        Place place = Place.builder()
                .region(region).primaryCategory(category)
                .name(name).normalizedName(name)
                .isGoodPrice(false).isHiddenGem(false).reviewCount(0)
                .build();
        em.persist(place);
        return place;
    }
}
