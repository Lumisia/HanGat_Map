package com.example.hangat.domain.weather.model.entity;

import com.example.hangat.domain.weather.model.enums.PrecipitationType;
import com.example.hangat.domain.weather.model.enums.WeatherGranularity;
import com.example.hangat.map.model.entity.DataSource;
import com.example.hangat.map.model.entity.Region;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 권역 날씨 예보 - 테이블 명세서 17.0 (Flyway V3)
 *
 * <p>메인 주간 날씨(MAIN), 권역 날씨 레이어(MAP_005), 코스 생성의 실내 우선 판단(COURSE_004),
 * 저장 코스의 "그때 예보 vs 지금 예보" 비교(MY_001, MY_008)가 이 테이블을 본다.
 * 출처는 기상청 단기예보(KMA_SHORT, D+0~3)와 중기예보(KMA_MID, D+4~7).
 *
 * <p><b>발표 시각(base_at)별 append 이력이다.</b> {@link com.example.hangat.map.model.entity.CongestionForecast}와
 * 같은 구조로, 다른 발표분을 덮어쓰지 않는다. 덮어쓰면 코스가 저장될 때 본 예보를 잃어 "예보가 바뀌었어요"를
 * 말할 수 없다. 그래서 created_at/updated_at도 없다. <b>같은 발표분</b>을 다시 적재할 때만 {@link #refreshFrom}으로
 * 값을 갱신한다 - 지우고 다시 넣으면 id가 바뀌어 course_items 스냅숏(ON DELETE SET NULL)이 조용히 사라진다.
 *
 * <p><b>정직성</b>: 단기예보는 권역 대표 격자(regions.kma_grid_x/y) 값이라 권역별로 다르지만,
 * 중기예보는 기상청이 제주도 단위로만 발표하므로 네 권역에 같은 값이 들어간다. 출처 코드로 구분되니
 * 화면은 KMA_MID 행을 "제주 전역 중기예보"로 표기해야 한다. 값이 없는 항목은 NULL로 두고 0으로 채우지 않는다.
 */
@Entity
@Table(
        name = "weather_forecasts",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_weather_region_forecast_base_gran",
                columnNames = {"region_id", "forecast_at", "base_at", "granularity"}),
        indexes = {
                @Index(name = "idx_weather_region_forecast", columnList = "region_id, forecast_at, base_at"),
                @Index(name = "idx_weather_base_at", columnList = "base_at")
        }
)
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class WeatherForecast {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** Region.id가 Short라 region_id는 smallint - 명세서 SMALLINT UNSIGNED와 맞는다. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "region_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_weather_region"))
    private Region region;

    /**
     * 예보 대상 시각. <b>UTC로 저장한다</b> - DAILY는 제주 기준일 00:00을 UTC로 바꾼 값(전날 15:00).
     * 변환은 적재 서비스가 한 곳에서만 하고({@code PlaceNameNormalizer.jejuDayToUtc}) 여기서는 받은 값을 그대로 쓴다.
     */
    @Column(name = "forecast_at", nullable = false)
    private LocalDateTime forecastAt;

    /**
     * 예보 발표 시각 = 발표 버전 식별자. 기상청은 발표 시각(base_date/base_time, tmFc)을 주므로
     * 집중률과 달리 실제 값을 넣는다. 같은 발표분을 다시 적재하면 UNIQUE가 버전을 늘리지 않는다.
     */
    @Column(name = "base_at", nullable = false)
    private LocalDateTime baseAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "granularity", length = 10, nullable = false)
    private WeatherGranularity granularity;

    /** 맑음 / 구름많음 / 흐림 등 화면 표기 문자열. 기상청 SKY 코드를 적재 시 한글로 옮긴다. */
    @Column(name = "sky_code", length = 20)
    private String skyCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "precipitation_type", length = 20)
    private PrecipitationType precipitationType;

    /** 시간 예보용 기온(℃). DAILY 행은 NULL. */
    @Column(name = "temperature", precision = 4, scale = 1)
    private BigDecimal temperature;

    @Column(name = "temp_min", precision = 4, scale = 1)
    private BigDecimal tempMin;

    @Column(name = "temp_max", precision = 4, scale = 1)
    private BigDecimal tempMax;

    /** 강수확률 %(0~100). 명세서 TINYINT UNSIGNED라 Byte - 100은 signed tinyint 범위 안이다. */
    @Column(name = "rain_probability")
    private Byte rainProbability;

    @Column(name = "precipitation_mm", precision = 7, scale = 2)
    private BigDecimal precipitationMm;

    @Column(name = "snow_cm", precision = 6, scale = 2)
    private BigDecimal snowCm;

    @Column(name = "wind_speed", precision = 5, scale = 2)
    private BigDecimal windSpeed;

    @Column(name = "humidity")
    private Byte humidity;

    /** KMA_SHORT / KMA_MID. 화면이 "권역 단기" 와 "제주 전역 중기" 를 가르는 기준이다. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_code", nullable = false,
            foreignKey = @ForeignKey(name = "fk_weather_source"))
    private DataSource source;

    @Column(name = "fetched_at", nullable = false)
    private LocalDateTime fetchedAt;

    /** JPA 전용. */
    protected WeatherForecast() {
    }

    /**
     * 하루 요약 행. 값이 없는 항목은 null로 넘기면 그대로 NULL이 된다 - 0으로 메우지 않는다.
     *
     * @param forecastAt      제주 기준일 00:00을 UTC로 바꾼 값
     * @param rainProbability 0~100. 범위 검증은 호출부(적재)가 먼저 한다
     */
    public static WeatherForecast daily(Region region, DataSource source,
                                        LocalDateTime forecastAt, LocalDateTime baseAt,
                                        String skyCode, PrecipitationType precipitationType,
                                        Integer tempMin, Integer tempMax, Integer rainProbability) {
        return WeatherForecast.builder()
                .region(region)
                .source(source)
                .forecastAt(forecastAt)
                .baseAt(baseAt)
                .granularity(WeatherGranularity.DAILY)
                .skyCode(skyCode)
                .precipitationType(precipitationType)
                .tempMin(tempMin == null ? null : BigDecimal.valueOf(tempMin))
                .tempMax(tempMax == null ? null : BigDecimal.valueOf(tempMax))
                .rainProbability(rainProbability == null ? null : rainProbability.byteValue())
                .build();
    }

    /**
     * 같은 발표분 재적재 - 예보 값과 수집 시각만 갱신한다. 키(권역·대상 시각·발표 시각·단위)와 id는 그대로라
     * 이 행을 가리키는 코스 스냅숏이 살아남는다.
     */
    public void refreshFrom(WeatherForecast fresh) {
        this.skyCode = fresh.skyCode;
        this.precipitationType = fresh.precipitationType;
        this.tempMin = fresh.tempMin;
        this.tempMax = fresh.tempMax;
        this.rainProbability = fresh.rainProbability;
        this.source = fresh.source;
        this.fetchedAt = LocalDateTime.now();
    }

    /** 강수확률을 화면·계산이 쓰기 편한 Integer로. NULL이면 그대로 NULL. */
    public Integer rainProbabilityPercent() {
        return rainProbability == null ? null : Integer.valueOf(rainProbability);
    }

    @PrePersist
    void onCreate() {
        if (this.fetchedAt == null) {
            this.fetchedAt = LocalDateTime.now();
        }
    }
}
