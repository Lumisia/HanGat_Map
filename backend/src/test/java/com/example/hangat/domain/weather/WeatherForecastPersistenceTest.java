package com.example.hangat.domain.weather;

import com.example.hangat.domain.weather.model.entity.WeatherForecast;
import com.example.hangat.domain.weather.model.enums.PrecipitationType;
import com.example.hangat.domain.weather.model.enums.WeatherGranularity;
import com.example.hangat.domain.weather.repository.WeatherForecastRepository;
import com.example.hangat.map.model.entity.DataSource;
import com.example.hangat.map.model.entity.Region;
import com.example.hangat.map.repository.DataSourceRepository;
import com.example.hangat.map.repository.RegionRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * weather_forecasts(17.0) 엔티티·리포지토리 - 발표 버전 append 이력 규칙을 못 박는다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class WeatherForecastPersistenceTest {

    private static final LocalDateTime DAY1 = LocalDateTime.of(2026, 9, 9, 15, 0);   // KST 9/10 00:00
    private static final LocalDateTime DAY2 = LocalDateTime.of(2026, 9, 10, 15, 0);  // KST 9/11 00:00
    private static final LocalDateTime BASE_OLD = LocalDateTime.of(2026, 9, 8, 17, 0);
    private static final LocalDateTime BASE_NEW = LocalDateTime.of(2026, 9, 9, 17, 0);

    @Autowired WeatherForecastRepository repository;
    @Autowired RegionRepository regionRepository;
    @Autowired DataSourceRepository dataSourceRepository;
    @Autowired EntityManager em;

    private Region north;
    private Region east;
    private DataSource kmaShort;

    @BeforeEach
    void setUp() {
        north = regionRepository.findAll().stream()
                .filter(r -> r.getCode().equals("NORTH")).findFirst().orElseThrow();
        east = regionRepository.findAll().stream()
                .filter(r -> r.getCode().equals("EAST")).findFirst().orElseThrow();
        kmaShort = dataSourceRepository.findById("KMA_SHORT").orElseThrow();
    }

    @Test
    @DisplayName("하루 요약 행을 저장하고 그대로 읽는다 - 없는 값은 0이 아니라 NULL")
    void savesDailyRow() {
        WeatherForecast saved = repository.saveAndFlush(WeatherForecast.daily(
                north, kmaShort, DAY1, BASE_NEW, "맑음", PrecipitationType.NONE, 24, 29, 30));
        em.clear();

        WeatherForecast found = repository.findById(saved.getId()).orElseThrow();
        assertThat(found.getGranularity()).isEqualTo(WeatherGranularity.DAILY);
        assertThat(found.getTempMin()).isEqualByComparingTo(new BigDecimal("24"));
        assertThat(found.getTempMax()).isEqualByComparingTo(new BigDecimal("29"));
        assertThat(found.rainProbabilityPercent()).isEqualTo(30);
        assertThat(found.getSkyCode()).isEqualTo("맑음");
        assertThat(found.getTemperature()).isNull();
        assertThat(found.getHumidity()).isNull();
        assertThat(found.getFetchedAt()).isNotNull();
        assertThat(found.getRegion().getCode()).isEqualTo("NORTH");
        assertThat(found.getSource().getCode()).isEqualTo("KMA_SHORT");
    }

    @Test
    @DisplayName("같은 권역·날짜·발표 시각·단위는 한 행만 - 같은 발표분을 두 번 넣으면 UNIQUE에 걸린다")
    void rejectsDuplicateVersion() {
        repository.saveAndFlush(WeatherForecast.daily(
                north, kmaShort, DAY1, BASE_NEW, "맑음", PrecipitationType.NONE, 24, 29, 30));

        assertThatThrownBy(() -> repository.saveAndFlush(WeatherForecast.daily(
                north, kmaShort, DAY1, BASE_NEW, "흐림", PrecipitationType.RAIN, 23, 27, 70)))
                .isInstanceOf(DataIntegrityViolationException.class)
                // 다른 무결성 오류로 대신 통과하는 허위 통과 방지 - 제약 이름까지 못 박는다(H2는 대문자)
                .hasMessageContaining("UK_WEATHER_REGION_FORECAST_BASE_GRAN");
    }

    @Test
    @DisplayName("다른 발표 시각은 같은 날짜라도 append 된다 - 최신 버전 조회와 버전 단위 삭제가 구분된다")
    void keepsVersionsAndDeletesOnlyOne() {
        repository.save(WeatherForecast.daily(north, kmaShort, DAY1, BASE_OLD, "흐림", PrecipitationType.RAIN, 22, 26, 70));
        repository.save(WeatherForecast.daily(north, kmaShort, DAY1, BASE_NEW, "맑음", PrecipitationType.NONE, 24, 29, 30));
        repository.save(WeatherForecast.daily(north, kmaShort, DAY2, BASE_NEW, "구름많음", PrecipitationType.NONE, 23, 28, 20));
        repository.save(WeatherForecast.daily(east, kmaShort, DAY1, BASE_NEW, "비", PrecipitationType.RAIN, 23, 27, 80));
        repository.flush();

        assertThat(repository.findLatestBaseAt(WeatherGranularity.DAILY)).contains(BASE_NEW);
        assertThat(repository.findLatestBaseAt(WeatherGranularity.HOURLY)).isEmpty();

        assertThat(repository.findByRegionIdAndBaseAtAndGranularityOrderByForecastAtAsc(
                north.getId(), BASE_NEW, WeatherGranularity.DAILY))
                .extracting(WeatherForecast::getForecastAt)
                .containsExactly(DAY1, DAY2);

        assertThat(repository.findFirstByRegionIdAndForecastAtAndGranularityOrderByBaseAtDesc(
                north.getId(), DAY1, WeatherGranularity.DAILY))
                .map(WeatherForecast::getBaseAt).contains(BASE_NEW);

        assertThat(repository.findByBaseAtAndGranularityOrderByRegionIdAscForecastAtAsc(
                BASE_NEW, WeatherGranularity.DAILY)).hasSize(3);

        // 날짜별 최신: DAY1은 BASE_NEW 행만, DAY2도 포함, 동부는 제외
        assertThat(repository.findLatestPerDate(north.getId(), DAY1, DAY2, WeatherGranularity.DAILY))
                .extracting(WeatherForecast::getForecastAt, WeatherForecast::getBaseAt)
                .containsExactly(tuple(DAY1, BASE_NEW), tuple(DAY2, BASE_NEW));

        int removed = repository.deleteVersion(BASE_NEW, WeatherGranularity.DAILY);
        assertThat(removed).isEqualTo(3);
        assertThat(repository.findLatestBaseAt(WeatherGranularity.DAILY)).contains(BASE_OLD);
        assertThat(repository.count()).isEqualTo(1);
    }
}
