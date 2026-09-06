package com.example.hangat.domain.weather;

import com.example.hangat.domain.weather.model.entity.WeatherForecast;
import com.example.hangat.domain.weather.model.enums.WeatherGranularity;
import com.example.hangat.domain.weather.repository.WeatherForecastRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 날씨 예보 발표 버전 저장 전담.
 *
 * <p>별도 클래스인 이유는 {@code CongestionIngestWriter}와 같다 - 같은 클래스 안에서 {@code @Transactional}
 * 메서드를 부르면 프록시를 안 거쳐 트랜잭션이 안 걸린다.
 *
 * <p><b>지우고 다시 넣지 않는다(upsert).</b> 같은 발표분(base_at)의 기존 행은 값만 갱신하고 없는 키만 넣는다.
 * 삭제+삽입이면 (1) id가 바뀌어 course_items 스냅숏이 ON DELETE SET NULL로 조용히 사라지고,
 * (2) 한 권역만 실패한 재실행이 그 권역의 기존 행까지 지운다. 받지 못한 권역·날짜는 그냥 손대지 않는다.
 * 한 발표 버전은 많아야 권역 4 × 날짜 4 = 16행이라 한 트랜잭션이면 된다.
 */
@Component
public class WeatherIngestWriter {

    private static final Logger log = LoggerFactory.getLogger(WeatherIngestWriter.class);

    private final WeatherForecastRepository repository;

    public WeatherIngestWriter(WeatherForecastRepository repository) {
        this.repository = repository;
    }

    public record Upserted(int inserted, int updated) {
    }

    @Transactional
    public Upserted upsertVersion(LocalDateTime baseAtUtc, List<WeatherForecast> rows) {
        Map<String, WeatherForecast> existing = new HashMap<>();
        for (WeatherForecast stored : repository.findByBaseAtAndGranularityOrderByRegionIdAscForecastAtAsc(
                baseAtUtc, WeatherGranularity.DAILY)) {
            existing.put(key(stored), stored);
        }
        int inserted = 0;
        int updated = 0;
        for (WeatherForecast row : rows) {
            WeatherForecast current = existing.get(key(row));
            if (current == null) {
                repository.save(row);
                inserted++;
            } else {
                current.refreshFrom(row);   // 영속 상태라 더티 체킹으로 UPDATE - id 보존
                updated++;
            }
        }
        repository.flush();
        if (updated > 0) {
            log.info("같은 발표 버전 {}행 값 갱신(id 보존), {}행 추가 baseAt(UTC)={}", updated, inserted, baseAtUtc);
        }
        return new Upserted(inserted, updated);
    }

    /** 권역 id는 프록시여도 초기화 없이 읽힌다. */
    private static String key(WeatherForecast forecast) {
        return forecast.getRegion().getId() + "@" + forecast.getForecastAt();
    }
}
