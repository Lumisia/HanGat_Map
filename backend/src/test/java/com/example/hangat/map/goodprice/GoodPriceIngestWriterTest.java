package com.example.hangat.map.goodprice;

import com.example.hangat.map.goodprice.GoodPriceCsv.Row;
import com.example.hangat.map.goodprice.KakaoLocalClient.GeoPoint;
import com.example.hangat.map.model.entity.DataSource;
import com.example.hangat.map.model.entity.Place;
import com.example.hangat.map.model.entity.PlaceCategory;
import com.example.hangat.map.model.entity.Region;
import com.example.hangat.map.model.enums.BusinessStatus;
import com.example.hangat.map.repository.PlaceRepository;
import com.example.hangat.map.service.PlaceNameNormalizer;
import com.example.hangat.map.service.RegionResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 착한가격 저장 - 기존 매칭·신규 삽입·멱등·권역 제외 검증 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({GoodPriceIngestWriter.class, RegionResolver.class})
class GoodPriceIngestWriterTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private GoodPriceIngestWriter writer;

    @Autowired
    private PlaceRepository placeRepository;

    private PlaceCategory food;

    @BeforeEach
    void setUp() {
        em.persist(Region.builder().code("WEST").name("서부").displayOrder((byte) 1).build());
        em.persist(Region.builder().code("SOUTH").name("남부").displayOrder((byte) 2).build());
        food = em.persist(PlaceCategory.builder().code("FOOD").name("음식점").build());
        em.persist(DataSource.builder()
                .code("MOIS_GOODPRICE").displayName("착한가격업소").providerName("행정안전부")
                .attributionText("출처: 제주시 착한가격업소 정보")
                .displayOrder((short) 4).isActive(true).build());
        em.flush();
    }

    private Row row(String name, String address) {
        return new Row("제주시", "한식", name, "064-000-0000", address, List.of("강김밥 2,900원"));
    }

    @Test
    void 신규_업소가_좌표와_함께_들어간다() {
        boolean inserted = writer.insertNew(
                row("강김밥집", "제주시 애월읍 하귀9길 2"),
                new GeoPoint(new BigDecimal("33.48"), new BigDecimal("126.40")));

        assertThat(inserted).isTrue();
        Place saved = placeRepository.findByNormalizedName("강김밥집").get(0);
        assertThat(saved.isGoodPrice()).isTrue();
        assertThat(saved.getOverview()).contains("강김밥 2,900원");
        assertThat(saved.getRegion().getCode()).isEqualTo("WEST");
        assertThat(saved.getLatitude()).isEqualByComparingTo("33.48");
    }

    @Test
    void 이름과_읍면동이_같은_기존_장소는_플래그만_켠다() {
        Place 기존 = em.persist(Place.builder()
                .region(em.getEntityManager().createQuery("from Region where code='WEST'", Region.class).getSingleResult())
                .primaryCategory(food)
                .name("강김밥집").normalizedName("강김밥집")
                .roadAddress("제주특별자치도 제주시 애월읍 하귀9길 2")
                .isGoodPrice(false).isHiddenGem(false).reviewCount(0)
                .build());
        em.flush();

        GoodPriceIngestWriter.Outcome outcome = writer.upsertMatched(row("강김밥집", "제주시 애월읍 하귀9길 2, 1층"));

        assertThat(outcome).isEqualTo(GoodPriceIngestWriter.Outcome.MATCHED);
        assertThat(기존.isGoodPrice()).isTrue();
        // 새 장소를 만들지 않았다 - 지도에 같은 가게 핀이 두 개 찍히면 안 된다
        assertThat(placeRepository.findByNormalizedName("강김밥집")).hasSize(1);
    }

    @Test
    void 이름이_같아도_다른_동네면_다른_가게다() {
        em.persist(Place.builder()
                .region(em.getEntityManager().createQuery("from Region where code='SOUTH'", Region.class).getSingleResult())
                .primaryCategory(food)
                .name("강김밥집").normalizedName("강김밥집")
                .roadAddress("서귀포시 대정읍 어딘가 1")
                .isGoodPrice(false).isHiddenGem(false).reviewCount(0)
                .build());
        em.flush();

        assertThat(writer.upsertMatched(row("강김밥집", "제주시 애월읍 하귀9길 2")))
                .isEqualTo(GoodPriceIngestWriter.Outcome.NONE);
    }

    @Test
    void 재실행하면_이미_적재한_건은_건너뛴다() {
        writer.insertNew(row("강김밥집", "제주시 애월읍 하귀9길 2"),
                new GeoPoint(new BigDecimal("33.48"), new BigDecimal("126.40")));

        assertThat(writer.upsertMatched(row("강김밥집", "제주시 애월읍 하귀9길 2")))
                .isEqualTo(GoodPriceIngestWriter.Outcome.ALREADY);
    }

    @Test
    void 같은_읍면동이면_같은_가게_후보다() {
        assertThat(GoodPriceIngestWriter.sameTown(
                "제주특별자치도 제주시 애월읍 하귀9길 2", "제주시 애월읍 다른길 5")).isTrue();
        assertThat(GoodPriceIngestWriter.sameTown(
                "제주시 애월읍 하귀9길 2", "서귀포시 대정읍 하모상가로 43")).isFalse();
        assertThat(GoodPriceIngestWriter.sameTown(null, "제주시 애월읍 1")).isFalse();
    }

    @Test
    void 추자도는_권역_밖이라_넣지_않는다() {
        boolean inserted = writer.insertNew(row("추자식당", "제주시 추자면 대서리 1"),
                new GeoPoint(new BigDecimal("33.95"), new BigDecimal("126.30")));

        assertThat(inserted).isFalse();
    }

    private static GeoPoint geo() {
        return new GeoPoint(new BigDecimal("33.48"), new BigDecimal("126.40"));
    }

    private Place saved(String name) {
        return placeRepository.findByNormalizedName(PlaceNameNormalizer.normalize(name)).get(0);
    }

    @Test
    void CSV_내용이_바뀌면_가격_문단과_기준일을_갱신하고_같은_내용은_멱등이다() {
        writer.insertNew(row("강김밥집", "제주시 애월읍 하귀9길 2"), geo());
        em.flush();

        Row changed = new Row("제주시", "한식", "강김밥집", "064-000-0000", "제주시 애월읍 하귀9길 2", List.of("강김밥 3,500원"));
        assertThat(writer.upsertMatched(changed)).isEqualTo(GoodPriceIngestWriter.Outcome.REFRESHED);
        assertThat(writer.upsertMatched(changed)).isEqualTo(GoodPriceIngestWriter.Outcome.ALREADY);

        assertThat(saved("강김밥집").getOverview()).contains("3,500원").doesNotContain("2,900원");
    }

    @Test
    void CSV에서_빠진_업소는_지정을_해제하고_가격_문단을_지운다() {
        String[] names = {"가김밥집", "나김밥집", "다김밥집", "라김밥집", "마김밥집"};
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < names.length; i++) {
            Row r = row(names[i], "제주시 애월읍 하귀9길 " + (i + 1));
            writer.insertNew(r, geo());
            if (i > 0) seen.add(GoodPriceIngestWriter.sourceIdOf(r));   // 첫 가게만 CSV 에서 빠졌다
        }
        em.flush();

        GoodPriceIngestWriter.ClearResult r = writer.clearMissing(seen);
        em.flush();
        em.clear();

        assertThat(r.cleared()).isEqualTo(1);
        Place gone = saved("가김밥집");
        assertThat(gone.isGoodPrice()).isFalse();
        assertThat(gone.getGoodPriceBaseDate()).isNull();
        assertThat(gone.getOverview()).isNull();
        // 지정 해제는 폐업이 아니다 - 상태는 건드리지 않는다
        assertThat(gone.getBusinessStatus()).isEqualTo(BusinessStatus.UNKNOWN);
        assertThat(saved("나김밥집").isGoodPrice()).isTrue();
    }

    @Test
    void CSV_수신이_부족하면_해제를_보류한다() {
        String[] names = {"가김밥집", "나김밥집", "다김밥집", "라김밥집", "마김밥집"};
        for (int i = 0; i < names.length; i++) {
            writer.insertNew(row(names[i], "제주시 애월읍 하귀9길 " + (i + 1)), geo());
        }
        em.flush();

        GoodPriceIngestWriter.ClearResult r = writer.clearMissing(Set.of());

        assertThat(r.skipped()).isTrue();
        assertThat(saved("가김밥집").isGoodPrice()).isTrue();
    }
}
