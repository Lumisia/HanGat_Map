package com.example.hangat.map.repository;

import com.example.hangat.map.model.dto.PlaceListResponse;
import com.example.hangat.map.model.entity.Place;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 장소 조회 - 설계서 §2.1. 네이티브 쿼리 금지(§8.3, 테스트가 H2)라 전부 JPQL이다.
 *
 * <p><b>목록은 DTO 프로젝션, 상세는 fetch join</b>으로 나뉜다.
 * <ul>
 *   <li>목록: 엔티티를 한 건도 로드하지 않으므로 {@code region}·{@code primaryCategory} 지연로딩이 발생할 자리가 없고
 *       (N+1 원천 차단, OSIV 설정에도 안 기댄다), §9.1이 확정한 대로 {@code overview}(TEXT)를 SELECT하지 않는다.</li>
 *   <li>상세: {@code overview}·사진 등 필드가 계속 늘어나는 화면이라 엔티티를 fetch join으로 읽고 DTO가 변환한다.
 *       to-one 두 개는 {@code optional=false}(NOT NULL)라 inner join fetch가 안전하다.</li>
 * </ul>
 *
 * <p><b>distinct 금지.</b> {@code overview}는 H2에서 CLOB이고 H2 2.x는 CLOB에 {@code SELECT DISTINCT}를 거부한다
 * (MariaDB에서는 돌고 테스트에서만 깨진다). 여기 쿼리들은 전부 {@code @ManyToOne} 조인이라 행이 늘지 않아
 * distinct가 애초에 불필요하니 습관적으로도 붙이지 말 것. <b>단서</b>: 이건 to-one에서만 성립한다 -
 * 나중에 {@code @OneToMany}(place_images·reviews)를 join fetch로 붙이면 행이 늘어난다. 그때는 distinct가 아니라
 * 별도 쿼리로 나눠야 한다(상세 Optional이 NonUniqueResult로 깨지는 것도 같은 이유).
 *
 * <p>목록 SELECT 절은 {@link #LIST_SELECT} 한 곳에만 존재한다(문자열 리터럴 결합 = 컴파일타임 상수라 {@code @Query}에 들어간다).
 * 조건이 늘어도 조인·CLOSED 제외를 빠뜨릴 수 없다. 메서드 이름은 파생 쿼리 규칙이 아니라 용도로 지었다 -
 * 넷 다 {@code @Query} 전용이며 부팅 시 {@code em.createQuery}로 검증된다.
 */
public interface PlaceRepository extends JpaRepository<Place, Long> {

    /**
     * 목록 SELECT 절. <b>이 인자 순서가 곧 {@link PlaceListResponse} 생성자 시그니처</b>이며
     * 엔티티 필드 선언 순서(주소 → 좌표 → 편의정보)와 같게 맞춰 뒀다 - 다르게 두면 좌표 스왑이 눈에 안 띈다.
     *
     * <p><b>세부분류 조인이 행을 늘리지 않는 근거</b>: {@code sourceType = API}로 좁혀 두었고
     * KTO는 장소당 소분류를 하나만 준다. 운영자가 손으로 붙이는 태그는 {@code ADMIN}이라 여기 안 걸린다.
     * 나중에 한 장소에 API 태그를 둘 이상 붙이게 되면 <b>목록에 같은 장소가 두 줄 나온다</b> -
     * distinct로 덮지 말고(위 CLOB 단서 참고) 태그를 별도 쿼리로 분리할 것.
     * {@code left join}이라 미분류 장소도 목록에서 빠지지 않는다.
     *
     * <p>폐업(CLOSED)만 제외한다: 헛걸음을 만드는 '틀린 정보'라서 '없는 정보'인 UNKNOWN과 성격이 다르다.
     * TEMP_CLOSED·UNKNOWN은 남기고 상태값을 실어 화면이 배지로 표시한다
     * (KTO 적재분 2,138건이 전부 UNKNOWN이라 OPEN만 남기면 통째로 사라진다).
     */
    String LIST_SELECT = """
            select new com.example.hangat.map.model.dto.PlaceListResponse(
                p.id, p.name,
                r.code, r.name, c.code, c.name,
                t.code, t.name,
                p.roadAddress, p.lotAddress,
                p.latitude, p.longitude,
                p.phone, p.operatingHoursText,
                p.parkingAvailable, p.toiletAvailable,
                p.businessStatus, p.isGoodPrice, p.isHiddenGem)
            from Place p
                join p.region r
                join p.primaryCategory c
                left join PlaceTag pt
                    on pt.place = p
                    and pt.sourceType = com.example.hangat.map.model.enums.TagSourceType.API
                left join pt.tag t
            where p.businessStatus <> com.example.hangat.map.model.enums.BusinessStatus.CLOSED""";

    /** KTO 적재 순서를 유지한다. 이름순은 H2와 MariaDB의 한글 collation이 달라 테스트가 환경마다 흔들린다. */
    String LIST_ORDER = " order by p.id";

    /** type 생략 - 제주 전역 전체. */
    @Query(LIST_SELECT + LIST_ORDER)
    List<PlaceListResponse> findListAll();

    /** type=spot/cafe/stay/cvs/mart. */
    @Query(LIST_SELECT + " and c.code = :categoryCode" + LIST_ORDER)
    List<PlaceListResponse> findListOfCategory(@Param("categoryCode") String categoryCode);

    /** type=food. 착한가격업소는 카테고리가 아니라 플래그다(§2.1). */
    @Query(LIST_SELECT + " and p.isGoodPrice = :goodPrice" + LIST_ORDER)
    List<PlaceListResponse> findListOfGoodPrice(@Param("goodPrice") Boolean goodPrice);

    /** type=dine. */
    @Query(LIST_SELECT + " and c.code = :categoryCode and p.isGoodPrice = :goodPrice" + LIST_ORDER)
    List<PlaceListResponse> findListOfCategoryAndGoodPrice(@Param("categoryCode") String categoryCode,
                                                           @Param("goodPrice") Boolean goodPrice);

    /**
     * 통합 검색 (MAP_002) - 이름·메뉴(overview) 부분 일치. 화면의 필터(권역·업종 칩) 범위 안에서만 찾는다.
     * 좌표 없는 장소는 뺀다: 결과 클릭 = 지도 이동이라 좌표가 필수다.
     * 이름 일치를 메뉴 일치보다 앞세운다 - 검색 엔진 없이 내는 최소한의 연관도.
     * overview는 CLOB이라 SELECT에는 여전히 싣지 않고(§9.1) 조건으로만 쓴다.
     */
    String SEARCH_WHERE = """

             and p.latitude is not null and p.longitude is not null
             and (:region is null or r.code = :region)
             and (p.name like concat('%', :q, '%') or p.overview like concat('%', :q, '%'))""";

    /** 접두 일치 > 이름 포함 > 메뉴 매칭, 같은 급이면 짧은 이름 우선 - "성산" 검색에 성산일출봉이 성산점 지점명보다 위로 온다. */
    String SEARCH_ORDER = """

            order by case when p.name like concat(:q, '%') then 0
                          when p.name like concat('%', :q, '%') then 1
                          else 2 end,
                     length(p.name), p.id""";

    /** 업종 칩이 하나도 없거나 전부 켜진 상태 - 카테고리 조건 없이 찾는다. */
    @Query(LIST_SELECT + SEARCH_WHERE + SEARCH_ORDER)
    List<PlaceListResponse> searchList(@Param("q") String q,
                                       @Param("region") String region,
                                       Pageable pageable);

    /** 켜진 업종 칩의 카테고리 안에서만. JPQL의 in은 빈 목록을 못 받아 쿼리를 나눈다 - 분기는 서비스가 한다. */
    @Query(LIST_SELECT + SEARCH_WHERE + " and c.code in :categories" + SEARCH_ORDER)
    List<PlaceListResponse> searchListInCategories(@Param("q") String q,
                                                   @Param("region") String region,
                                                   @Param("categories") List<String> categories,
                                                   Pageable pageable);

    /**
     * 상세. 연관 2개를 한 쿼리에서 초기화한다.
     * 폐업도 그대로 돌려준다 - 찜·공유 링크로 들어오는 경로라 404로 감추면 폐업 사실조차 전달하지 못한다.
     */
    @Query("select p from Place p join fetch p.region join fetch p.primaryCategory where p.id = :id")
    Optional<Place> findDetailById(@Param("id") Long id);

    /**
     * 상세의 세부분류. 목록과 달리 한 건짜리 조회라 조인을 얹지 않고 쿼리를 하나 더 쓴다 -
     * fetch join을 늘리면 {@code Optional}이 NonUniqueResult로 깨질 여지만 생긴다.
     */
    @Query("""
            select t.code, t.name
            from PlaceTag pt
              join pt.tag t
            where pt.place.id = :placeId
              and pt.sourceType = com.example.hangat.map.model.enums.TagSourceType.API
            """)
    List<Object[]> findApiTagOf(@Param("placeId") Long placeId);

    /**
     * 상세(detailIntro2)를 아직 안 받은 장소. 쿼터가 하루 1,000콜이라 나눠 도는데,
     * 커서를 따로 저장하지 않고 '비어 있는 것부터' 집어 자연히 이어지게 한다.
     *
     * <p>한계: KTO가 세 값을 다 안 주는 장소는 매번 다시 시도된다. 실측 후 조정할 것.
     */
    @Query("""
            select p from Place p
            where p.operatingHoursText is null
              and p.restDayText is null
              and p.useFeeText is null
            order by p.id
            """)
    List<Place> findWithoutDetail(Pageable pageable);

    /** 메뉴(overview)가 아직 없는 음식점부터. KTO가 메뉴를 안 주는 곳도 다시 잡힌다 - 상세 적재의 empty 와 같은 트레이드오프 */
    @Query("""
            select p from Place p
            where p.primaryCategory.code = 'FOOD'
              and p.overview is null
            order by p.id
            """)
    List<Place> findFoodWithoutMenu(Pageable pageable);

    List<Place> findByNormalizedName(String normalizedName);

    /** 사진이 아직 없는 장소부터. KTO에 사진이 0장인 곳도 다시 잡힌다 - 상세 적재의 empty 와 같은 트레이드오프 */
    @Query("""
            select p from Place p
            where not exists (select 1 from PlaceImage i where i.place = p)
            order by p.id
            """)
    List<Place> findWithoutImage(Pageable pageable);

    /**
     * 이름 매칭용 (id, normalized_name) 목록.
     *
     * <p>집중률 API가 고유 ID를 안 줘서 이름으로만 이어붙일 수 있다(설계서 §3.6).
     * 2,138건뿐이라 배치가 시작할 때 통째로 읽어 메모리 맵으로 쓴다 -
     * 9,856행을 한 건씩 조회하면 그만큼 쿼리가 나간다.
     */
    @Query("""
            select p.id, p.normalizedName
            from Place p
            where p.businessStatus <> com.example.hangat.map.model.enums.BusinessStatus.CLOSED
            """)
    List<Object[]> findIdAndNormalizedName();

    /**
     * 대안 스왑용 후보 조회 - 같은 카테고리 + 바운딩 박스 선필터 (정밀 거리는 GeoService가 2차 컷).
     * 팀 규칙상 네이티브 대신 JPQL. region은 응답 표시명에 바로 쓰므로 fetch join.
     */
    @Query("""
            select p from Place p
            join fetch p.region
            where p.primaryCategory.code = :categoryCode
              and p.latitude between :minLat and :maxLat
              and p.longitude between :minLng and :maxLng
            """)
    List<Place> findCandidatesInBox(String categoryCode,
                                    BigDecimal minLat, BigDecimal maxLat,
                                    BigDecimal minLng, BigDecimal maxLng);

}
