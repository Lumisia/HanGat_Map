package com.example.hangat.map.service;

import com.example.hangat.map.model.dto.CrowdForecastResponse;
import com.example.hangat.map.repository.CongestionForecastRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 혼잡 예보 조회 - 설계서 §2.2 / 커밋 4-3
 *
 * <p>DB는 (장소, 대상일, 발표버전) 행 단위로 쌓여 있고, 화면은 장소별 날짜순 배열을 원한다.
 * 그 사이를 바꿔 주는 것이 이 클래스의 전부다.
 */
@Service
@Transactional(readOnly = true)
public class CrowdForecastService {

    private static final Logger log = LoggerFactory.getLogger(CrowdForecastService.class);

    private final CongestionForecastRepository forecastRepository;

    public CrowdForecastService(CongestionForecastRepository forecastRepository) {
        this.forecastRepository = forecastRepository;
    }

    /**
     * 최신 발표 버전의 예보 전체.
     *
     * <p><b>발표 버전을 고정하는 것이 핵심이다.</b> 이 테이블은 덮어쓰기 금지라 같은 날짜에 대해
     * 어제 발표와 오늘 발표가 함께 쌓여 있다. 버전을 안 고르면 한 장소·한 날짜에 값이 두 개 나온다.
     */
    public CrowdForecastResponse getForecast() {
        Optional<LocalDateTime> latest = forecastRepository.findLatestBaseAt();
        if (latest.isEmpty()) {
            log.warn("혼잡 예보가 한 건도 없다 - 적재 배치를 돌리지 않았을 수 있다");
            return CrowdForecastResponse.empty();
        }

        List<Object[]> rows = forecastRepository.findVersionRows(latest.get());
        if (rows.isEmpty()) {
            return CrowdForecastResponse.empty();
        }
        // base_at은 적재 시각의 제주 날짜를 그대로 넣은 값이라 UTC 되돌리기를 하지 않는다
        // (forecast_at만 jejuDayToUtc로 저장된다 - CongestionIngestService 참고).
        return assemble(latest.get().toLocalDate(), rows);
    }

    /**
     * 행 목록을 장소별 날짜순 배열로 편다.
     *
     * <p><b>날짜를 인덱스로 계산하는 이유</b>: 장소마다 예보가 있는 날이 다르다. 정렬 순서대로
     * 배열에 밀어 넣으면 중간에 하루 빠진 장소는 <b>그 뒤가 전부 하루씩 당겨진다</b> -
     * 값이 그럴듯해서 화면에서는 알아챌 수 없고, '가장 한산한 날'이 엉뚱한 날짜로 나온다.
     */
    private CrowdForecastResponse assemble(LocalDate baseDate, List<Object[]> rows) {
        LocalDate from = null;
        LocalDate to = null;
        for (Object[] row : rows) {
            LocalDate day = PlaceNameNormalizer.utcToJejuDay((LocalDateTime) row[1]);
            if (from == null || day.isBefore(from)) {
                from = day;
            }
            if (to == null || day.isAfter(to)) {
                to = day;
            }
        }
        int days = (int) ChronoUnit.DAYS.between(from, to) + 1;

        Map<String, List<BigDecimal>> values = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String placeId = String.valueOf(row[0]);
            LocalDate day = PlaceNameNormalizer.utcToJejuDay((LocalDateTime) row[1]);
            int index = (int) ChronoUnit.DAYS.between(from, day);

            List<BigDecimal> series = values.computeIfAbsent(placeId, k -> nullSeries(days));
            series.set(index, (BigDecimal) row[2]);
        }

        log.debug("혼잡 예보 조립: 장소 {}곳 × {}일 (발표분 {}, from={})", values.size(), days, baseDate, from);
        return new CrowdForecastResponse(baseDate, from, days, values);
    }

    /** 값이 채워지지 않은 날은 null로 남는다 - 0으로 채우면 '가장 한산한 날'로 뽑힌다. */
    private List<BigDecimal> nullSeries(int days) {
        return new ArrayList<>(Arrays.asList(new BigDecimal[days]));
    }
}
