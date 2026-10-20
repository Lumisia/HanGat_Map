package com.example.hangat.map.repository;

import com.example.hangat.map.model.entity.CongestionForecast;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 혼잡 예보 조회 - 설계서 §2.2.
 *
 * <p><b>이 테이블은 같은 날짜에 대해 여러 발표 버전이 쌓인다</b>(덮어쓰기 금지가 명세서 요구사항).
 * 그래서 화면용 조회는 <b>반드시 {@code base_at}을 고정</b>해야 한다 - 안 그러면 어제 발표와
 * 오늘 발표가 같이 나와 한 장소가 두 줄로 보인다.
 *
 * <p>배치가 한 번에 전 장소를 넣으므로 <b>전역 최신 {@code base_at} 하나</b>면 충분하다
 * ({@link #findLatestBaseAt()}). 장소별 상관 서브쿼리로 최신을 고르는 방식은 같은 결과를 내면서
 * 훨씬 비싸다.
 */
public interface CongestionForecastRepository extends JpaRepository<CongestionForecast, Long> {

    /** 가장 최근 발표 버전. 데이터가 한 건도 없으면 비어 있다 - 화면은 전부 '정보 없음'이 된다. */
    @Query("select max(f.baseAt) from CongestionForecast f")
    Optional<LocalDateTime> findLatestBaseAt();

    /** 좌측 순위 목록용 - 특정 날짜 한 판. 정렬·필터는 조회 API(4-3)가 얹는다. */
    List<CongestionForecast> findByBaseAtAndForecastAt(LocalDateTime baseAt, LocalDateTime forecastAt);

    /** 상세 화면의 날짜별 예보 22일치. */
    @Query("""
            select f from CongestionForecast f
            where f.place.id = :placeId and f.baseAt = :baseAt
            order by f.forecastAt
            """)
    List<CongestionForecast> findSeriesOf(@Param("placeId") Long placeId,
                                          @Param("baseAt") LocalDateTime baseAt);

    /**
     * 한 장소·한 날짜의 예보 한 건 - 스왑이 교체 장소의 스냅숏을 박을 때 쓴다.
     *
     * <p>하루 전체를 읽는 {@code findByBaseAtAndForecastAt}(수백 건)과 달리 한 건만 필요할 때
     * 쓰라고 따로 둔다. baseAt까지 조건에 넣어야 발표 버전이 섞이지 않는다(클래스 주석 참고).
     */
    @Query("""
            select f from CongestionForecast f
            where f.place.id = :placeId and f.forecastAt = :forecastAt and f.baseAt = :baseAt
            """)
    Optional<CongestionForecast> findOne(@Param("placeId") Long placeId,
                                         @Param("forecastAt") LocalDateTime forecastAt,
                                         @Param("baseAt") LocalDateTime baseAt);

    /** 같은 버전을 다시 적재하려는지 판정한다 - 배치를 하루에 두 번 돌렸을 때. */
    boolean existsByBaseAt(LocalDateTime baseAt);

    /** 단발성 코스 배치가 필요한 날짜의 당일 적재 여부를 확인한다. */
    boolean existsByBaseAtAndForecastAt(LocalDateTime baseAt, LocalDateTime forecastAt);

    /**
     * 한 발표 버전에 예보가 있는 장소 id - 관광공사 집중률 집계 대상(= 주요 관광지) 목록.
     * 숨은 명소 판정(HiddenGemRule)이 "유명도 하위"의 기준으로 쓴다.
     */
    @Query("select distinct f.place.id from CongestionForecast f where f.baseAt = :baseAt")
    List<Long> findPlaceIdsOfVersion(@Param("baseAt") LocalDateTime baseAt);

    /**
     * 화면용 전체 조회 - 한 발표 버전의 (place_id, forecast_at, rate) 전부.
     *
     * <p>엔티티가 아니라 <b>필요한 세 값만</b> 뽑는다. 7,000행을 엔티티로 읽으면
     * 영속성 컨텍스트에 그만큼 쌓이는데, 조회 전용이라 하나도 쓸 데가 없다.
     * {@code f.place.id}는 FK 컬럼이라 join 없이 나온다.
     */
    @Query("""
            select f.place.id, f.forecastAt, f.rate
            from CongestionForecast f
            where f.baseAt = :baseAt
            order by f.place.id, f.forecastAt
            """)
    List<Object[]> findVersionRows(@Param("baseAt") LocalDateTime baseAt);

    /**
     * 여러 장소의 특정 날짜 집중률 - 찜 목록(favorite)의 '오늘 혼잡'. (placeId, rate).
     * baseAt 을 함께 고정해야 발표 버전이 섞이지 않는다(클래스 주석). 그 날짜 예보가 없는 장소는 행이 없다.
     */
    @Query("""
            select f.place.id, f.rate
            from CongestionForecast f
            where f.baseAt = :baseAt
              and f.forecastAt = :forecastAt
              and f.place.id in :placeIds
            """)
    List<Object[]> findRatesOn(@Param("baseAt") LocalDateTime baseAt,
                               @Param("forecastAt") LocalDateTime forecastAt,
                               @Param("placeIds") Collection<Long> placeIds);

    /**
     * 같은 발표 버전만 지운다. 재적재용이다.
     *
     * <p>파생 삭제({@code deleteByBaseAt} 이름 규칙)를 쓰면 7,600행을 전부 엔티티로 로드한 뒤
     * 한 건씩 지운다. 벌크 DELETE 한 방으로 끝낸다.
     *
     * <p>⚠️ 벌크 연산은 영속성 컨텍스트를 건너뛰므로 <b>같은 트랜잭션에서 이미 읽어 둔 엔티티가
     * 있으면 그 캐시가 낡는다</b>. 여기는 지우고 새로 넣기만 해서 문제가 없다.
     */
    @Modifying
    @Query("delete from CongestionForecast f where f.baseAt = :baseAt")
    int deleteVersion(@Param("baseAt") LocalDateTime baseAt);
}
