package com.example.hangat.map.repository;

import com.example.hangat.map.model.entity.CongestionForecast;
import com.example.hangat.map.model.entity.DataSource;
import com.example.hangat.map.model.entity.Place;
import com.example.hangat.map.model.entity.PlaceCategory;
import com.example.hangat.map.model.entity.Region;
import com.example.hangat.map.model.enums.CongestionLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 혼잡 예보 - 단계 파생 경계값과 발표 버전 분리 검증.
 *
 * <p><b>두 번째·세 번째 테스트가 이 커밋의 급소다.</b>
 * <ul>
 *   <li>경계값: {@code 40}이 QUIET인지 NORMAL인지가 화면 핀 색을 가른다. 부등호를 뒤집어도
 *       컴파일은 되고 값도 그럴듯해서 눈으로는 못 잡는다</li>
 *   <li>발표 버전: {@code base_at}을 안 고정하고 조회하면 어제 발표와 오늘 발표가 같이 나와
 *       한 장소가 목록에 두 줄로 보인다. 데이터가 하루치뿐일 때는 멀쩡해 보이다가
 *       이틀째부터 드러난다</li>
 * </ul>
 *
 * <p>{@code replace = NONE} 사유는 {@link PlaceRepositoryTest} 참고.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class CongestionForecastRepositoryTest {

    private static final LocalDateTime 어제발표 = LocalDateTime.of(2026, 8, 23, 0, 0);
    private static final LocalDateTime 오늘발표 = LocalDateTime.of(2026, 8, 24, 0, 0);
    private static final LocalDateTime 대상일 = LocalDateTime.of(2026, 8, 25, 0, 0);

    @Autowired
    private TestEntityManager em;

    @Autowired
    private CongestionForecastRepository repository;

    private Place 금오름;
    private Place 성산일출봉;
    private DataSource 집중률;

    @BeforeEach
    void setUp() {
        Region west = em.getEntityManager().createQuery("from Region where code = 'WEST'", Region.class)
                .getResultStream().findFirst().orElseGet(() ->
                        em.persist(Region.builder().code("WEST").name("서부").displayOrder((byte) 1).build()));
        PlaceCategory tourist = em.getEntityManager().createQuery(
                        "from PlaceCategory where code = 'TOURIST'", PlaceCategory.class)
                .getResultStream().findFirst().orElseGet(() ->
                        em.persist(PlaceCategory.builder().code("TOURIST").name("관광지").build()));
        집중률 = em.find(DataSource.class, "KTO_CNCTR");
        if (집중률 == null) 집중률 = em.persist(DataSource.builder()
                .code("KTO_CNCTR").displayName("한국관광공사 관광지별 집중률")
                .providerName("한국관광공사").attributionText("한국관광공사")
                .displayOrder((short) 1).isActive(true)
                .build());
        금오름 = persistPlace(west, tourist, "금오름");
        성산일출봉 = persistPlace(west, tourist, "성산일출봉");
        em.flush();
    }

    @Test
    void 집중률_경계값이_단계를_가른다() {
        // 39.99 / 40 / 69.99 / 70 - 부등호 하나만 뒤집혀도 여기서 걸린다
        assertThat(CongestionLevel.from(new BigDecimal("39.99"))).isEqualTo(CongestionLevel.QUIET);
        assertThat(CongestionLevel.from(new BigDecimal("40.00"))).isEqualTo(CongestionLevel.NORMAL);
        assertThat(CongestionLevel.from(new BigDecimal("69.99"))).isEqualTo(CongestionLevel.NORMAL);
        assertThat(CongestionLevel.from(new BigDecimal("70.00"))).isEqualTo(CongestionLevel.CROWDED);
        assertThat(CongestionLevel.from(new BigDecimal("100.00"))).isEqualTo(CongestionLevel.CROWDED);
        assertThat(CongestionLevel.from(new BigDecimal("9.14"))).isEqualTo(CongestionLevel.QUIET);
    }

    @Test
    void 집중률이_없으면_한산이_아니라_예외다() {
        // 조용히 QUIET로 떨어뜨리면 '정보 없음'이 '한산'으로 둔갑한다 - 명세서가 금지한 동작
        assertThatThrownBy(() -> CongestionLevel.from(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 조회는_발표_버전을_고정해야_한_장소가_한_줄로_나온다() {
        save(금오름, 어제발표, 대상일, "55.00");
        save(금오름, 오늘발표, 대상일, "22.00");   // 같은 날짜에 대한 새 발표
        em.flush();
        em.clear();

        LocalDateTime latest = repository.findLatestBaseAt().orElseThrow();
        assertThat(latest).isEqualTo(오늘발표);

        List<CongestionForecast> rows = repository.findByBaseAtAndForecastAt(latest, 대상일);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getRate()).isEqualByComparingTo("22.00");
        assertThat(rows.get(0).getLevel()).isEqualTo(CongestionLevel.QUIET);

        // 어제 발표는 지워지지 않고 남아 있다 - 명세서의 "덮어쓰기 금지, 버전별 append"
        assertThat(repository.findAll()).hasSize(2);
        assertThat(repository.existsByBaseAt(어제발표)).isTrue();
    }

    @Test
    void 상세는_한_장소의_날짜별_예보를_시간순으로_준다() {
        save(금오름, 오늘발표, 대상일.plusDays(2), "80.00");
        save(금오름, 오늘발표, 대상일, "30.00");
        save(금오름, 오늘발표, 대상일.plusDays(1), "50.00");
        save(성산일출봉, 오늘발표, 대상일, "90.00");   // 다른 장소는 섞이면 안 된다
        em.flush();
        em.clear();

        List<CongestionForecast> series = repository.findSeriesOf(금오름.getId(), 오늘발표);

        assertThat(series).hasSize(3);
        assertThat(series).extracting(CongestionForecast::getForecastAt)
                .containsExactly(대상일, 대상일.plusDays(1), 대상일.plusDays(2));
        assertThat(series).extracting(CongestionForecast::getLevel)
                .containsExactly(CongestionLevel.QUIET, CongestionLevel.NORMAL, CongestionLevel.CROWDED);
    }

    @Test
    void 같은_장소_같은_날짜_같은_발표는_한_행뿐이다() {
        save(금오름, 오늘발표, 대상일, "30.00");
        em.flush();
        em.clear();

        // 배치를 같은 날 두 번 돌려도 행이 늘지 않는다 (UNIQUE place_id+forecast_at+base_at).
        // PlaceTag와 달리 여기는 PK가 자동 생성이라 save()가 '새 행'으로 보고 INSERT를 날린다
        // -> merge로 조용히 덮어써지는 게 아니라 DB 제약에 걸려 터진다.
        // 예외가 save() 안에서 날 수도, flush()에서 날 수도 있어 둘 다 범위에 넣는다
        assertThatThrownBy(() -> {
            save(금오름, 오늘발표, 대상일, "31.00");
            em.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    private void save(Place place, LocalDateTime baseAt, LocalDateTime forecastAt, String rate) {
        repository.save(CongestionForecast.of(place, 집중률, forecastAt, baseAt, new BigDecimal(rate)));
    }

    private Place persistPlace(Region region, PlaceCategory category, String name) {
        Place place = Place.builder()
                .region(region).primaryCategory(category)
                .name(name).normalizedName(name)
                .build();
        em.persist(place);
        return place;
    }
}
