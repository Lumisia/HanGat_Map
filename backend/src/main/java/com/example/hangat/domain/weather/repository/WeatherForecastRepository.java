package com.example.hangat.domain.weather.repository;

import com.example.hangat.domain.weather.model.entity.WeatherForecast;
import com.example.hangat.domain.weather.model.enums.WeatherGranularity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 권역 날씨 예보 조회.
 *
 * <p>같은 날짜에 발표 버전이 여러 개 쌓이므로 화면용 조회는 <b>{@code base_at}을 고정</b>해야 한다 -
 * 안 그러면 어제 발표와 오늘 발표가 같이 나와 한 날짜가 두 줄로 보인다.
 * 적재가 한 번에 네 권역을 다 넣으므로 단위별 전역 최신 {@code base_at} 하나면 충분하다.
 */
public interface WeatherForecastRepository extends JpaRepository<WeatherForecast, Long> {

    /** 가장 최근 발표 버전. 데이터가 없으면 비어 있다 - 그때 화면은 라이브 호출로 폴백한다. */
    @Query("select max(f.baseAt) from WeatherForecast f where f.granularity = :granularity")
    Optional<LocalDateTime> findLatestBaseAt(@Param("granularity") WeatherGranularity granularity);

    /** 한 발표 버전의 전 권역 예보 - 메인 주간 날씨·권역 레이어용. */
    List<WeatherForecast> findByBaseAtAndGranularityOrderByRegionIdAscForecastAtAsc(
            LocalDateTime baseAt, WeatherGranularity granularity);

    /** 한 권역·한 발표 버전의 날짜별 예보. */
    List<WeatherForecast> findByRegionIdAndBaseAtAndGranularityOrderByForecastAtAsc(
            Short regionId, LocalDateTime baseAt, WeatherGranularity granularity);

    /**
     * 한 권역의 날짜 구간을 <b>날짜마다 가장 최근 발표분</b>으로 - 메인 주간 날씨·권역 레이어의 기본 조회.
     *
     * <p>버전을 전역 하나로 고정하지 않는 이유: 단기(05시 발표)와 중기(06시 발표)가 다른 base_at을 갖고,
     * 어제 중기가 덮던 날짜를 오늘 단기가 덮으면 그 날짜만 새 버전으로 바뀐다. 날짜별 최신을 골라야
     * 한 주가 "가능한 가장 새 예보"로 채워진다.
     */
    @Query("""
            select f from WeatherForecast f
            where f.region.id = :regionId
              and f.granularity = :granularity
              and f.forecastAt between :fromUtc and :toUtc
              and f.baseAt = (
                  select max(g.baseAt) from WeatherForecast g
                  where g.region = f.region and g.forecastAt = f.forecastAt and g.granularity = f.granularity)
            order by f.forecastAt
            """)
    List<WeatherForecast> findLatestPerDate(@Param("regionId") Short regionId,
                                            @Param("fromUtc") LocalDateTime fromUtc,
                                            @Param("toUtc") LocalDateTime toUtc,
                                            @Param("granularity") WeatherGranularity granularity);

    /** 한 권역·한 날짜의 최신 발표 예보 - 코스가 저장 시점 스냅숏을 박을 때 쓴다. */
    Optional<WeatherForecast> findFirstByRegionIdAndForecastAtAndGranularityOrderByBaseAtDesc(
            Short regionId, LocalDateTime forecastAt, WeatherGranularity granularity);

    /**
     * 한 발표 버전 삭제 - <b>적재는 쓰지 않는다</b>(재적재는 {@code WeatherIngestWriter#upsertVersion}이 값만 갱신).
     * 수동 정리·테스트용. course_items 스냅숏 FK가 ON DELETE SET NULL이라 코스가 이 삭제를 막지는 않지만,
     * 지운 행을 가리키던 스냅숏은 '날씨 정보 없음'이 된다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from WeatherForecast f where f.baseAt = :baseAt and f.granularity = :granularity")
    int deleteVersion(@Param("baseAt") LocalDateTime baseAt,
                      @Param("granularity") WeatherGranularity granularity);
}
