package com.example.hangat.map.place;

import com.example.hangat.map.model.entity.DataSource;
import com.example.hangat.map.model.entity.Place;
import com.example.hangat.map.model.entity.PlaceCategory;
import com.example.hangat.map.model.entity.PlaceSourceMapping;
import com.example.hangat.map.model.entity.Region;
import com.example.hangat.map.model.enums.BusinessStatus;
import com.example.hangat.map.repository.PlaceRepository;
import com.example.hangat.map.repository.PlaceSourceMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 출석 체크 - 1회 미출현은 비활성만, 다른 날 2회 연속이면 폐업(같은 날 재실행은 보류), 재출현이면 복귀, 수신 부족이면 판정 보류 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(PlacePresenceReconciler.class)
class PlacePresenceReconcilerTest {

    @Autowired
    private TestEntityManager em;
    @Autowired
    private PlacePresenceReconciler reconciler;
    @Autowired
    private PlaceRepository placeRepository;
    @Autowired
    private PlaceSourceMappingRepository mappingRepository;

    private Region region;
    private PlaceCategory tourist;
    private DataSource kto;
    private DataSource sbiz;

    @BeforeEach
    void setUp() {
        region = em.persist(Region.builder().code("WEST").name("서부").displayOrder((byte) 1).build());
        tourist = em.persist(PlaceCategory.builder().code("TOURIST").name("관광지").build());
        kto = em.persist(source("KTO", "관광공사"));
        sbiz = em.persist(source("SBIZ", "소상공인"));
        em.flush();
    }

    private static DataSource source(String code, String name) {
        return DataSource.builder().code(code).displayName(name).providerName(name)
                .attributionText("출처: " + name).displayOrder((short) 1).isActive(true).build();
    }

    private Place place(String name) {
        return em.persist(Place.builder().region(region).primaryCategory(tourist)
                .name(name).normalizedName(name).build());
    }

    private PlaceSourceMapping mapping(Place place, DataSource source, String sourceId, boolean active) {
        return em.persist(PlaceSourceMapping.builder().place(place).source(source)
                .sourcePlaceId(sourceId).isActive(active).build());
    }

    private void reload() {
        em.flush();
        em.clear();
    }

    /** 지난 적재(어제)에서 비활성이 된 것으로 되돌린다 - @PreUpdate 를 타지 않는 벌크 갱신, 저장이 끝난 뒤 불러야 한다 */
    private void struckYesterday(PlaceSourceMapping m) {
        em.flush();
        em.getEntityManager()
                .createQuery("update PlaceSourceMapping m set m.updatedAt = :t where m.id = :id")
                .setParameter("t", LocalDateTime.now().minusDays(1))
                .setParameter("id", m.getId())
                .executeUpdate();
        em.clear();
    }

    @Test
    void 처음_안_보이면_매핑만_비활성이고_장소는_그대로다() {
        Place a = place("A");
        PlaceSourceMapping m = mapping(a, kto, "1", true);
        for (int i = 2; i <= 5; i++) {
            mapping(place("P" + i), kto, String.valueOf(i), true);
        }
        reload();

        // 5곳 중 4곳 수신(80%) - 가드를 통과하는 최소선에서 A 하나만 빠진 상황
        PlacePresenceReconciler.Result r = reconciler.reconcile("KTO", Set.of("2", "3", "4", "5"));
        reload();

        assertThat(r.struck()).isEqualTo(1);
        assertThat(r.closed()).isZero();
        assertThat(mappingRepository.findById(m.getId()).orElseThrow().isActive()).isFalse();
        assertThat(placeRepository.findById(a.getId()).orElseThrow().getBusinessStatus())
                .isEqualTo(BusinessStatus.UNKNOWN);
    }

    @Test
    void 어제_비활성이_된_장소가_오늘도_안_보이면_폐업이다() {
        Place a = place("A");
        PlaceSourceMapping m = mapping(a, kto, "1", false);   // 지난 적재에서 이미 스트라이크
        Place b = place("B");
        mapping(b, kto, "2", true);
        reload();
        struckYesterday(m);

        PlacePresenceReconciler.Result r = reconciler.reconcile("KTO", Set.of("2"));
        reload();

        assertThat(r.closed()).isEqualTo(1);
        assertThat(r.deferred()).isZero();
        assertThat(placeRepository.findById(a.getId()).orElseThrow().getBusinessStatus())
                .isEqualTo(BusinessStatus.CLOSED);
    }

    @Test
    void 같은_날_두_번_돌아도_폐업하지_않는다_최종점검_62() {
        Place a = place("A");
        PlaceSourceMapping m = mapping(a, kto, "1", true);
        for (int i = 2; i <= 5; i++) {
            mapping(place("P" + i), kto, String.valueOf(i), true);
        }
        reload();

        // 02:20 회차에서 A 결석 → 비활성. 같은 새벽 Job 이 뒷단계에서 실패해 재시도되면 한 번 더 돈다
        PlacePresenceReconciler.Result first = reconciler.reconcile("KTO", Set.of("2", "3", "4", "5"));
        reload();
        PlacePresenceReconciler.Result retry = reconciler.reconcile("KTO", Set.of("2", "3", "4", "5"));
        reload();

        assertThat(first.struck()).isEqualTo(1);
        assertThat(retry.struck()).isZero();
        assertThat(retry.closed()).isZero();
        assertThat(retry.deferred()).isEqualTo(1);   // 오늘 비활성이 된 것은 다음 날로 미룬다
        assertThat(mappingRepository.findById(m.getId()).orElseThrow().isActive()).isFalse();
        assertThat(placeRepository.findById(a.getId()).orElseThrow().getBusinessStatus())
                .isEqualTo(BusinessStatus.UNKNOWN);
    }

    @Test
    void 다시_나타나면_매핑이_살아나고_폐업도_풀린다() {
        Place a = place("A");
        a.markClosed();
        PlaceSourceMapping m = mapping(a, kto, "1", false);
        reload();

        PlacePresenceReconciler.Result r = reconciler.reconcile("KTO", Set.of("1"));
        reload();

        assertThat(r.revived()).isEqualTo(1);
        assertThat(mappingRepository.findById(m.getId()).orElseThrow().isActive()).isTrue();
        // 영업 중인지는 모른다 - OPEN으로 낙관하지 않는다
        assertThat(placeRepository.findById(a.getId()).orElseThrow().getBusinessStatus())
                .isEqualTo(BusinessStatus.UNKNOWN);
    }

    @Test
    void 다른_출처가_아직_보고_있으면_폐업하지_않는다() {
        Place a = place("A");
        PlaceSourceMapping m = mapping(a, kto, "1", false);
        mapping(a, sbiz, "s-1", true);
        Place b = place("B");
        mapping(b, kto, "2", true);
        reload();
        struckYesterday(m);

        PlacePresenceReconciler.Result r = reconciler.reconcile("KTO", Set.of("2"));
        reload();

        assertThat(r.closed()).isZero();
        assertThat(r.deferred()).isZero();   // 보류가 아니라 다른 출처 보호로 남은 것
        assertThat(placeRepository.findById(a.getId()).orElseThrow().getBusinessStatus())
                .isEqualTo(BusinessStatus.UNKNOWN);
    }

    @Test
    void 수신이_활성_매핑의_80퍼센트_미만이면_판정을_건너뛴다() {
        for (int i = 1; i <= 5; i++) {
            mapping(place("P" + i), kto, String.valueOf(i), true);
        }
        reload();

        // 5곳 중 3곳만 내려옴(60%) - 응답 불완전으로 보고 아무것도 바꾸지 않는다
        PlacePresenceReconciler.Result r = reconciler.reconcile("KTO", Set.of("1", "2", "3"));
        reload();

        assertThat(r.skipped()).isTrue();
        assertThat(mappingRepository.findAllBySourceCodeWithPlace("KTO"))
                .allMatch(PlaceSourceMapping::isActive);
    }

    @Test
    void 이미_폐업한_장소는_다시_세지_않는다() {
        Place a = place("A");
        a.markClosed();
        PlaceSourceMapping m = mapping(a, kto, "1", false);
        Place b = place("B");
        mapping(b, kto, "2", true);
        reload();
        struckYesterday(m);

        PlacePresenceReconciler.Result r = reconciler.reconcile("KTO", Set.of("2"));

        assertThat(r.closed()).isZero();
        assertThat(r.struck()).isZero();
    }

    @Test
    void 착한가격_매핑은_존재_근거로_치지_않는다() {
        Place a = place("A");
        PlaceSourceMapping m = mapping(a, kto, "1", false);
        DataSource mois = em.persist(source("MOIS_GOODPRICE", "행안부"));
        mapping(a, mois, "g-1", true);
        Place b = place("B");
        mapping(b, kto, "2", true);
        reload();
        struckYesterday(m);

        PlacePresenceReconciler.Result r = reconciler.reconcile("KTO", Set.of("2"));
        reload();

        assertThat(r.closed()).isEqualTo(1);
        assertThat(placeRepository.findById(a.getId()).orElseThrow().getBusinessStatus())
                .isEqualTo(BusinessStatus.CLOSED);
    }
}
