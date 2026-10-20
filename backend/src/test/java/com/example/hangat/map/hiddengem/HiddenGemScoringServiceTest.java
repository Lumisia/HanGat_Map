package com.example.hangat.map.hiddengem;

import com.example.hangat.map.model.entity.CongestionForecast;
import com.example.hangat.map.model.entity.DataSource;
import com.example.hangat.map.model.entity.Place;
import com.example.hangat.map.model.entity.PlaceCategory;
import com.example.hangat.map.model.entity.PlaceSourceMapping;
import com.example.hangat.map.model.entity.Region;
import com.example.hangat.map.model.enums.CongestionLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 숨은 명소 판정 배치 - 집계 대상 여부는 최신 집중률 발표분으로 가르고, 발표분이 없으면 아무것도 바꾸지 않는다.
 * {@code replace = NONE} 사유는 PlaceRepositoryTest 참고.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({HiddenGemScoringService.class, HiddenGemRule.class})
class HiddenGemScoringServiceTest {

    private static final LocalDateTime 발표분 = LocalDateTime.of(2026, 9, 11, 0, 0);
    private static final LocalDateTime 대상일 = LocalDateTime.of(2026, 9, 12, 0, 0);

    @Autowired
    private TestEntityManager em;
    @Autowired
    private HiddenGemScoringService service;

    private Region east;
    private PlaceCategory tourist;
    private DataSource kto;
    private DataSource cnctr;

    @BeforeEach
    void setUp() {
        east = em.persist(Region.builder().code("EAST").name("동부").displayOrder((byte) 1).build());
        tourist = em.persist(PlaceCategory.builder().code("TOURIST").name("관광지").build());
        kto = em.persist(DataSource.builder().code("KTO").displayName("한국관광공사 TourAPI")
                .providerName("한국관광공사").attributionText("한국관광공사").displayOrder((short) 1).isActive(true).build());
        cnctr = em.persist(DataSource.builder().code("KTO_CNCTR").displayName("집중률")
                .providerName("한국관광공사").attributionText("한국관광공사").displayOrder((short) 2).isActive(true).build());
    }

    private Place ktoPlace(String name, boolean quality) {
        Place.PlaceBuilder builder = Place.builder().region(east).primaryCategory(tourist).name(name).normalizedName(name);
        if (quality) {
            builder.imageUrl("https://tong.visitkorea.or.kr/" + name + ".jpg")
                    .latitude(new BigDecimal("33.4")).longitude(new BigDecimal("126.7"))
                    .roadAddress("제주특별자치도 제주시 " + name).operatingHoursText("상시 개방");
        }
        Place place = em.persist(builder.build());
        em.persist(PlaceSourceMapping.builder().place(place).source(kto).sourcePlaceId("kto-" + name)
                .lastSyncedAt(LocalDateTime.of(2026, 9, 10, 0, 0)).build());
        return place;
    }

    private void forecast(Place place) {
        em.persist(CongestionForecast.builder().place(place).source(cnctr)
                .baseAt(발표분).forecastAt(대상일)
                .rate(new BigDecimal("55.00")).level(CongestionLevel.NORMAL)
                .fetchedAt(발표분).build());
    }

    /** 집계 명단이 얇지 않게 - 기준(MIN_FAMOUS_COUNT) 이상의 예보 보유 장소를 채운다 */
    private void enoughFamous() {
        for (int i = 0; i < HiddenGemScoringService.MIN_FAMOUS_COUNT; i++) {
            forecast(ktoPlace("유명관광지" + i, false));
        }
    }

    @Test
    void 집계_대상이_아닌_품질_충족_관광지만_숨은_명소가_된다() {
        Place 가문이오름 = ktoPlace("가문이오름", true);
        Place 성산일출봉 = ktoPlace("성산일출봉", true);
        Place 사진없는곳 = ktoPlace("사진없는곳", false);
        forecast(성산일출봉);
        enoughFamous();
        em.flush();

        HiddenGemScoringService.HiddenGemScoringResult result = service.score();
        em.flush();
        em.clear();

        assertThat(result.skipped()).isFalse();
        assertThat(result.famousCount()).isEqualTo(HiddenGemScoringService.MIN_FAMOUS_COUNT + 1);
        assertThat(result.evaluated()).isEqualTo(3 + HiddenGemScoringService.MIN_FAMOUS_COUNT);
        assertThat(result.hiddenGems()).isEqualTo(1);
        assertThat(result.famous()).isEqualTo(1 + HiddenGemScoringService.MIN_FAMOUS_COUNT);
        assertThat(result.baseAt()).isEqualTo(발표분);

        Place gem = em.find(Place.class, 가문이오름.getId());
        assertThat(gem.isHiddenGem()).isTrue();
        assertThat(gem.getHiddenGemScore()).isEqualByComparingTo("0.700");
        assertThat(gem.getHiddenGemAlgorithmVersion()).isEqualTo(HiddenGemRule.VERSION);
        assertThat(gem.getHiddenGemCalculatedAt()).isNotNull();

        Place famous = em.find(Place.class, 성산일출봉.getId());
        assertThat(famous.isHiddenGem()).isFalse();
        assertThat(famous.getHiddenGemScore()).isEqualByComparingTo("0.700");   // 점수는 판정과 무관하게 남긴다

        assertThat(em.find(Place.class, 사진없는곳.getId()).isHiddenGem()).isFalse();
    }

    @Test
    void 집중률_발표분이_없으면_판정을_건너뛰고_아무것도_바꾸지_않는다() {
        Place place = ktoPlace("가문이오름", true);
        em.flush();

        HiddenGemScoringService.HiddenGemScoringResult result = service.score();
        em.flush();
        em.clear();

        assertThat(result.skipped()).isTrue();
        assertThat(result.evaluated()).isZero();
        Place untouched = em.find(Place.class, place.getId());
        assertThat(untouched.isHiddenGem()).isFalse();
        assertThat(untouched.getHiddenGemAlgorithmVersion()).isNull();
    }

    @Test
    void 다시_돌리면_같은_결과이고_바뀐_것이_없으면_쓰지_않는다_멱등() {
        Place place = ktoPlace("가문이오름", true);
        enoughFamous();
        em.flush();

        HiddenGemScoringService.HiddenGemScoringResult first = service.score();
        em.flush();
        HiddenGemScoringService.HiddenGemScoringResult second = service.score();
        em.flush();
        em.clear();

        assertThat(first.changed()).isGreaterThan(0);
        assertThat(second.changed()).isZero();   // 매일 2천 행의 updated_at 을 흔들지 않는다
        assertThat(second.hiddenGems()).isEqualTo(1);
        assertThat(em.find(Place.class, place.getId()).isHiddenGem()).isTrue();
    }

    @Test
    void 집계_대상에_새로_오르면_숨은_명소에서_빠진다() {
        Place place = ktoPlace("뜬_오름", true);
        enoughFamous();
        em.flush();
        service.score();
        em.flush();
        assertThat(em.find(Place.class, place.getId()).isHiddenGem()).isTrue();

        forecast(place);   // 다음 발표분부터 관광공사 집계 대상이 됐다
        em.flush();
        service.score();
        em.flush();
        em.clear();

        assertThat(em.find(Place.class, place.getId()).isHiddenGem()).isFalse();
    }

    @Test
    void 집계_명단이_얇으면_부분_수신으로_보고_건너뛴다() {
        Place place = ktoPlace("가문이오름", true);
        forecast(ktoPlace("성산일출봉", true));   // 명단이 1곳뿐 - 집중률 API 부분 수신과 구분할 수 없다
        em.flush();

        HiddenGemScoringService.HiddenGemScoringResult result = service.score();
        em.flush();
        em.clear();

        assertThat(result.skipped()).isTrue();
        assertThat(result.famousCount()).isEqualTo(1);
        assertThat(em.find(Place.class, place.getId()).isHiddenGem()).isFalse();
    }

    @Test
    void 음식점에는_점수를_남기지_않는다() {
        PlaceCategory food = em.persist(PlaceCategory.builder().code("FOOD").name("음식점").build());
        Place restaurant = em.persist(Place.builder().region(east).primaryCategory(food).name("식당").normalizedName("식당")
                .imageUrl("x").latitude(BigDecimal.ONE).longitude(BigDecimal.ONE).roadAddress("주소").overview("소개").build());
        em.persist(PlaceSourceMapping.builder().place(restaurant).source(kto).sourcePlaceId("kto-food")
                .lastSyncedAt(LocalDateTime.of(2026, 9, 10, 0, 0)).build());
        enoughFamous();
        em.flush();

        service.score();
        em.flush();
        em.clear();

        assertThat(em.find(Place.class, restaurant.getId()).getHiddenGemScore()).isNull();
    }

    @Test
    void 출처에서_사라진_매핑은_판정하지_않는다() {
        Place place = em.persist(Place.builder().region(east).primaryCategory(tourist).name("사라진곳").normalizedName("사라진곳")
                .imageUrl("x").latitude(BigDecimal.ONE).longitude(BigDecimal.ONE).roadAddress("주소").overview("소개").build());
        em.persist(PlaceSourceMapping.builder().place(place).source(kto).sourcePlaceId("kto-gone")
                .lastSyncedAt(LocalDateTime.of(2026, 9, 10, 0, 0)).isActive(false).build());
        enoughFamous();
        em.flush();

        HiddenGemScoringService.HiddenGemScoringResult result = service.score();

        assertThat(result.evaluated()).isEqualTo(HiddenGemScoringService.MIN_FAMOUS_COUNT);   // 사라진 곳은 세지 않는다
        em.flush();
        em.clear();
        assertThat(em.find(Place.class, place.getId()).getHiddenGemAlgorithmVersion()).isNull();
    }
}
