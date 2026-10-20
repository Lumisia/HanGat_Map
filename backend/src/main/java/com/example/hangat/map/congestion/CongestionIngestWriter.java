package com.example.hangat.map.congestion;

import com.example.hangat.map.model.entity.CongestionForecast;
import com.example.hangat.map.model.entity.DataSource;
import com.example.hangat.map.model.entity.Place;
import com.example.hangat.map.repository.CongestionForecastRepository;
import com.example.hangat.map.repository.DataSourceRepository;
import com.example.hangat.map.repository.PlaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 혼잡 예보 청크 저장 전담.
 *
 * <p>별도 클래스인 이유는 {@link PlaceIngestWriter}와 같다 - 같은 클래스 안에서
 * {@code @Transactional} 메서드를 부르면 프록시를 안 거쳐 청크 단위 트랜잭션이 안 걸린다.
 */
@Component
public class CongestionIngestWriter {

    private static final Logger log = LoggerFactory.getLogger(CongestionIngestWriter.class);

    private final CongestionForecastRepository forecastRepository;
    private final PlaceRepository placeRepository;
    private final DataSourceRepository dataSourceRepository;

    public CongestionIngestWriter(CongestionForecastRepository forecastRepository,
                                  PlaceRepository placeRepository,
                                  DataSourceRepository dataSourceRepository) {
        this.forecastRepository = forecastRepository;
        this.placeRepository = placeRepository;
        this.dataSourceRepository = dataSourceRepository;
    }

    /** 저장 대상 한 건. 이름 매칭·날짜 변환이 끝난 상태로 넘어온다. */
    public record Row(Long placeId, LocalDateTime forecastAt, BigDecimal rate) {
    }

    /**
     * 같은 발표 버전을 지우고 다시 넣을 수 있게 한다.
     *
     * <p>명세서의 "덮어쓰기 금지"는 <b>어제 발표를 오늘 발표로 덮지 말라</b>는 뜻이다.
     * 같은 버전을 다시 적재하는 것(개발 중 재실행)은 그 규칙과 무관하고,
     * 안 지우면 UNIQUE에 걸려 청크가 통째로 롤백된다.
     *
     * @return 지운 행 수
     */
    @Transactional
    public int clearVersion(LocalDateTime baseAt) {
        int removed = forecastRepository.deleteVersion(baseAt);
        if (removed > 0) {
            log.warn("같은 발표 버전 {}행을 지우고 다시 넣는다 baseAt={} (다른 버전은 그대로 둔다)",
                    removed, baseAt);
        }
        return removed;
    }

    @Transactional
    public int saveChunk(String sourceCode, LocalDateTime baseAt, List<Row> rows) {
        DataSource source = dataSourceRepository.findById(sourceCode)
                .orElseThrow(() -> new IllegalStateException(
                        "data_sources에 '" + sourceCode + "' 행이 없다"));

        // 같은 장소가 22일치로 반복되므로 프록시를 재사용한다.
        // getReferenceById는 SELECT를 날리지 않는다 - FK 값만 쓸 거라 굳이 로드할 필요가 없다
        Map<Long, Place> places = new HashMap<>();
        int saved = 0;

        for (Row row : rows) {
            Place place = places.computeIfAbsent(row.placeId(), placeRepository::getReferenceById);
            forecastRepository.save(
                    CongestionForecast.of(place, source, row.forecastAt(), baseAt, row.rate()));
            saved++;
        }
        return saved;
    }
}
