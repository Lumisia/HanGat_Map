package com.example.hangat.map.detail;

import com.example.hangat.map.model.entity.Place;
import com.example.hangat.map.model.entity.PlaceCategory;
import com.example.hangat.map.model.entity.Region;
import com.example.hangat.map.repository.PlaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 소개글 저장 - 빈 곳만 채우고, 이미 있는 문단(착한가격 메뉴)은 덮지 않는다 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(OverviewIngestWriter.class)
class OverviewIngestWriterTest {

    @Autowired
    private TestEntityManager em;
    @Autowired
    private OverviewIngestWriter writer;
    @Autowired
    private PlaceRepository placeRepository;

    private Region west;
    private PlaceCategory tourist;

    @BeforeEach
    void setUp() {
        west = em.persist(Region.builder().code("WEST").name("서부").displayOrder((byte) 1).build());
        tourist = em.persist(PlaceCategory.builder().code("TOURIST").name("관광지").build());
    }

    private Place place(String name, String overview) {
        return em.persist(Place.builder().region(west).primaryCategory(tourist)
                .name(name).normalizedName(name).overview(overview).build());
    }

    @Test
    void 소개글이_없던_관광지에_채운다() {
        Place p = place("가시리풍력", null);
        em.flush();

        OverviewIngestWriter.ChunkResult r = writer.saveChunk(List.of(new OverviewIngestWriter.Row(p.getId(), "표선면 가시리는 …")));
        em.flush();
        em.clear();

        assertThat(r.updated()).isEqualTo(1);
        assertThat(placeRepository.findById(p.getId()).orElseThrow().getOverview()).isEqualTo("표선면 가시리는 …");
    }

    @Test
    void 이미_있는_문단은_덮지_않는다() {
        Place p = place("국밥집", "대표메뉴: 순대국밥 9,000원");
        em.flush();

        writer.saveChunk(List.of(new OverviewIngestWriter.Row(p.getId(), "소개글")));
        em.flush();
        em.clear();

        assertThat(placeRepository.findById(p.getId()).orElseThrow().getOverview()).isEqualTo("대표메뉴: 순대국밥 9,000원");
    }

    @Test
    void KTO가_안_준_곳은_empty로_센다() {
        Place p = place("소개 없음", null);
        em.flush();

        OverviewIngestWriter.ChunkResult r = writer.saveChunk(List.of(new OverviewIngestWriter.Row(p.getId(), null)));

        assertThat(r.updated()).isZero();
        assertThat(r.empty()).isEqualTo(1);
    }

    @Test
    void 관광지_대상_조회는_소개글_없는_곳만_id순으로() {
        Place a = place("A", null);
        place("B", "이미 있음");
        Place c = place("C", null);
        em.flush();

        List<Place> targets = placeRepository.findTouristWithoutOverview(org.springframework.data.domain.PageRequest.of(0, 10));

        assertThat(targets).extracting(Place::getId).containsExactly(a.getId(), c.getId());
    }
}
