package com.example.hangat.map.repository;

import com.example.hangat.map.model.dto.PlaceListResponse;
import com.example.hangat.map.model.entity.Place;
import com.example.hangat.map.model.entity.PlaceCategory;
import com.example.hangat.map.model.entity.PlaceTag;
import com.example.hangat.map.model.entity.Region;
import com.example.hangat.map.model.entity.Tag;
import com.example.hangat.map.model.enums.BusinessStatus;
import com.example.hangat.map.model.enums.TagSourceType;
import com.example.hangat.map.model.enums.TagType;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 목록 프로젝션·type 조건·N+1 방지 검증.
 *
 * <p><b>이 테스트는 장식이 아니라 목록 설계의 전제다.</b> 목록은 JPQL 생성자 프로젝션이라
 * 인자 순서가 밀려도 <b>같은 타입 자리에서는 컴파일러도 Hibernate도 못 잡는다</b>(§9.1이 경고한 좌표 스왑).
 * 그래서 한 건에 전 필드를 서로 다른 값으로 넣고 17개를 모두 대조한다.
 *
 * <p>{@code replace = NONE}: @DataJpaTest 기본값은 임의 이름의 임베디드 DB로 갈아끼워
 * {@code application-test.yaml}의 {@code MODE=MariaDB}를 무시한다. 테스트가 dev를 검증하려면 그 URL을 그대로 써야 한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class PlaceRepositoryTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private PlaceRepository placeRepository;

    private Region west;
    private PlaceCategory tourist;
    private PlaceCategory food;
    private Tag 오름;

    @BeforeEach
    void setUp() {
        west = Region.builder().code("WEST").name("서부").displayOrder((byte) 1).build();
        tourist = PlaceCategory.builder().code("TOURIST").name("관광지").build();
        food = PlaceCategory.builder().code("FOOD").name("음식점").build();
        오름 = Tag.builder()
                .code("NA010100").name("산, 고개, 오름, 봉우리").description("자연관광 > 자연경관")
                .tagType(TagType.PLACE).isActive(true)
                .build();
        em.persist(west);
        em.persist(tourist);
        em.persist(food);
        em.persist(오름);
    }

    @Test
    void 목록_projection이_필드를_뒤바꾸지_않는다() {
        Place 금오름 = Place.builder()
                .region(west).primaryCategory(tourist)
                .name("금오름").normalizedName("금오름")
                .roadAddress("도로명주소값").lotAddress("지번주소값")
                .latitude(new BigDecimal("33.3560000")).longitude(new BigDecimal("126.3060000"))
                .overview("H2에서 CLOB이라 목록 SELECT에는 들어가지 않는다")
                .phone("064-000-0000").operatingHoursText("09:00~18:00")
                .parkingAvailable(true).toiletAvailable(false)
                .businessStatus(BusinessStatus.OPEN)
                .isGoodPrice(true).isHiddenGem(false)
                .build();
        em.persist(금오름);
        em.persist(PlaceTag.fromApi(금오름, 오름));
        em.flush();
        em.clear();

        PlaceListResponse found = findByName(placeRepository.findListAll(), "금오름");

        assertThat(found.getId()).isEqualTo(금오름.getId());
        assertThat(found.getRegionCode()).isEqualTo("WEST");
        assertThat(found.getRegionName()).isEqualTo("서부");
        assertThat(found.getCategoryCode()).isEqualTo("TOURIST");
        assertThat(found.getCategoryName()).isEqualTo("관광지");
        assertThat(found.getTagCode()).isEqualTo("NA010100");
        assertThat(found.getTagName()).isEqualTo("산, 고개, 오름, 봉우리");
        assertThat(found.getRoadAddress()).isEqualTo("도로명주소값");
        assertThat(found.getLotAddress()).isEqualTo("지번주소값");
        assertThat(found.getLatitude()).isEqualByComparingTo("33.356");
        assertThat(found.getLongitude()).isEqualByComparingTo("126.306");
        assertThat(found.getPhone()).isEqualTo("064-000-0000");
        assertThat(found.getOperatingHoursText()).isEqualTo("09:00~18:00");
        assertThat(found.getParkingAvailable()).isTrue();
        assertThat(found.getToiletAvailable()).isFalse();
        assertThat(found.getBusinessStatus()).isEqualTo(BusinessStatus.OPEN);
        assertThat(found.isGoodPrice()).isTrue();
        assertThat(found.isHiddenGem()).isFalse();
    }

    @Test
    void 미수집_필드는_null_그대로_내려간다() {
        Place 상시개방 = place("상시개방오름", tourist, false, BusinessStatus.UNKNOWN);
        em.flush();
        em.clear();

        PlaceListResponse found = findByName(placeRepository.findListAll(), "상시개방오름");

        // hours=null은 '상시 개방'이라는 정보다. 빈 문자열·false로 채우면 화면이 없는 정보를 그린다(§1.2)
        assertThat(found.getOperatingHoursText()).isNull();
        assertThat(found.getPhone()).isNull();
        assertThat(found.getParkingAvailable()).isNull();
        assertThat(found.getToiletAvailable()).isNull();
        assertThat(found.getLatitude()).isNull();
        assertThat(상시개방.getId()).isNotNull();
    }

    @Test
    void 세부분류가_없어도_목록에서_빠지지_않는다() {
        Place 분류있음 = place("분류있는곳", tourist, false, BusinessStatus.UNKNOWN);
        place("분류없는곳", tourist, false, BusinessStatus.UNKNOWN);
        em.persist(PlaceTag.fromApi(분류있음, 오름));
        em.flush();
        em.clear();

        // left join이라서다. inner join이면 미분류 장소가 지도에서 통째로 사라지는데,
        // 목록 건수만 보고는 '원래 그만큼인가' 싶어 알아채기 어렵다
        List<PlaceListResponse> all = placeRepository.findListAll();
        assertThat(names(all)).contains("분류있는곳", "분류없는곳");
        assertThat(findByName(all, "분류없는곳").getTagName()).isNull();
    }

    @Test
    void 운영자_태그는_목록_세부분류에_섞이지_않는다() {
        Place 금오름 = place("금오름", tourist, false, BusinessStatus.UNKNOWN);
        em.persist(PlaceTag.builder()
                .place(금오름).tag(오름)
                .weight(java.math.BigDecimal.ONE).sourceType(TagSourceType.ADMIN)
                .build());
        em.flush();
        em.clear();

        // 조인이 sourceType=API로 좁혀져 있다. 안 좁히면 ADMIN 태그가 붙는 순간
        // 같은 장소가 목록에 두 줄 나오고, 지도에는 마커가 겹쳐 찍힌다
        List<PlaceListResponse> all = placeRepository.findListAll();
        assertThat(names(all)).containsExactly("금오름");
        assertThat(all.get(0).getTagName()).isNull();
    }

    @Test
    void type별_조회조건이_설계서_2_1표와_일치한다() {
        place("성산일출봉", tourist, false, BusinessStatus.OPEN);
        place("착한가격식당", food, true, BusinessStatus.OPEN);
        place("일반식당", food, false, BusinessStatus.OPEN);
        em.flush();
        em.clear();

        // spot = 카테고리만 / food = 플래그만 / dine = 카테고리 + 플래그
        assertThat(names(placeRepository.findListOfCategory("TOURIST")))
                .contains("성산일출봉").doesNotContain("착한가격식당", "일반식당");
        assertThat(names(placeRepository.findListOfGoodPrice(true)))
                .contains("착한가격식당").doesNotContain("성산일출봉", "일반식당");
        assertThat(names(placeRepository.findListOfCategoryAndGoodPrice("FOOD", false)))
                .contains("일반식당").doesNotContain("성산일출봉", "착한가격식당");
        assertThat(names(placeRepository.findListAll()))
                .contains("성산일출봉", "착한가격식당", "일반식당");
        assertThat(placeRepository.findListAll().stream().map(PlaceListResponse::getId).toList()).isSorted();
    }

    @Test
    void 폐업은_목록에서_빠지지만_상세로는_조회된다() {
        Place 폐업 = place("폐업한가게", food, false, BusinessStatus.CLOSED);
        place("상태미수집", tourist, false, BusinessStatus.UNKNOWN);
        place("임시휴업", food, false, BusinessStatus.TEMP_CLOSED);
        em.flush();
        em.clear();

        // UNKNOWN을 빼면 시드 82곳이 통째로 사라진다(엔티티 기본값이 UNKNOWN)
        assertThat(names(placeRepository.findListAll()))
                .contains("상태미수집", "임시휴업").doesNotContain("폐업한가게");
        assertThat(placeRepository.findDetailById(폐업.getId())).isPresent();
    }

    @Test
    void 상세는_연관_두_개를_한_쿼리로_초기화한다() {
        Place 관광지 = Place.builder()
                .region(west).primaryCategory(tourist)
                .name("소개글있는곳").normalizedName("소개글있는곳")
                .overview("CLOB 컬럼 - distinct를 쓰지 않으므로 H2에서도 조회된다")
                .build();
        em.persist(관광지);
        em.flush();
        em.clear();

        Place found = placeRepository.findDetailById(관광지.getId()).orElseThrow();

        assertThat(Hibernate.isInitialized(found.getRegion())).isTrue();
        assertThat(Hibernate.isInitialized(found.getPrimaryCategory())).isTrue();
        assertThat(found.getOverview()).startsWith("CLOB 컬럼");
    }

    @Test
    void 없는_id는_빈_Optional이다() {
        assertThat(placeRepository.findDetailById(999_999L)).isEmpty();
    }

    @Test
    void 검색은_접두_이름_메뉴_순으로_연관도를_매긴다() {
        placeWithCoords("바다식당", food, "대표메뉴: 성게국수 9,000원");
        placeWithCoords("제주국수본가", food, null);
        placeWithCoords("국수바다", food, null);
        placeWithCoords("성산일출봉", tourist, null);
        em.flush();
        em.clear();

        List<PlaceListResponse> found = placeRepository.searchList("국수", null, PageRequest.of(0, 20));

        // 접두 일치(국수바다) > 이름 포함(제주국수본가) > 메뉴 매칭(바다식당)
        assertThat(names(found)).containsExactly("국수바다", "제주국수본가", "바다식당");
    }

    @Test
    void 검색은_좌표없는_장소와_폐업을_뺀다() {
        // 결과 클릭 = 지도 이동이라 좌표 없는 곳은 데려갈 수 없다
        place("좌표없는국수집", food, false, BusinessStatus.OPEN);
        Place 폐업 = Place.builder()
                .region(west).primaryCategory(food)
                .name("폐업국수").normalizedName("폐업국수")
                .latitude(new BigDecimal("33.1")).longitude(new BigDecimal("126.1"))
                .businessStatus(BusinessStatus.CLOSED).isGoodPrice(false)
                .build();
        em.persist(폐업);
        em.flush();
        em.clear();

        assertThat(placeRepository.searchList("국수", null, PageRequest.of(0, 20))).isEmpty();
    }

    @Test
    void 검색은_limit_상위만_돌려준다() {
        placeWithCoords("국수1", food, null);
        placeWithCoords("국수2", food, null);
        placeWithCoords("국수3", food, null);
        em.flush();
        em.clear();

        assertThat(placeRepository.searchList("국수", null, PageRequest.of(0, 2))).hasSize(2);
    }

    @Test
    void 검색은_권역과_카테고리_필터를_존중한다() {
        Region east = Region.builder().code("EAST").name("동부").displayOrder((byte) 2).build();
        em.persist(east);
        Place 동부국수 = Place.builder()
                .region(east).primaryCategory(food)
                .name("동부국수").normalizedName("동부국수")
                .latitude(new BigDecimal("33.4")).longitude(new BigDecimal("126.8"))
                .businessStatus(BusinessStatus.OPEN).isGoodPrice(false)
                .build();
        em.persist(동부국수);
        placeWithCoords("서부국수", food, null);
        placeWithCoords("국수오름", tourist, null);
        em.flush();
        em.clear();

        // 권역만 - 화면의 권역 칩과 같은 범위
        assertThat(names(placeRepository.searchList("국수", "WEST", PageRequest.of(0, 20))))
                .contains("서부국수", "국수오름").doesNotContain("동부국수");
        // 카테고리만 - 화면의 업종 칩과 같은 범위
        assertThat(names(placeRepository.searchListInCategories("국수", null, List.of("FOOD"), PageRequest.of(0, 20))))
                .contains("동부국수", "서부국수").doesNotContain("국수오름");
    }

    private Place placeWithCoords(String name, PlaceCategory category, String overview) {
        Place place = Place.builder()
                .region(west).primaryCategory(category)
                .name(name).normalizedName(name)
                .latitude(new BigDecimal("33.3")).longitude(new BigDecimal("126.3"))
                .overview(overview)
                .businessStatus(BusinessStatus.OPEN).isGoodPrice(false)
                .build();
        em.persist(place);
        return place;
    }

    private Place place(String name, PlaceCategory category, boolean goodPrice, BusinessStatus status) {
        Place place = Place.builder()
                .region(west).primaryCategory(category)
                .name(name).normalizedName(name)
                .businessStatus(status).isGoodPrice(goodPrice)
                .build();
        em.persist(place);
        return place;
    }

    private List<String> names(List<PlaceListResponse> places) {
        return places.stream().map(PlaceListResponse::getName).toList();
    }

    private PlaceListResponse findByName(List<PlaceListResponse> places, String name) {
        return places.stream().filter(p -> name.equals(p.getName())).findFirst().orElseThrow();
    }
}
