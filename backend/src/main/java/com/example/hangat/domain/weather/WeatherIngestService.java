package com.example.hangat.domain.weather;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.domain.weather.KmaIssueTimes.Issue;
import com.example.hangat.domain.weather.WeatherDailySummarizer.DailySummary;
import com.example.hangat.domain.weather.model.MidLandItem;
import com.example.hangat.domain.weather.model.MidTaItem;
import com.example.hangat.domain.weather.model.ShortTermItem;
import com.example.hangat.domain.weather.model.entity.WeatherForecast;
import com.example.hangat.map.model.entity.DataSource;
import com.example.hangat.map.model.entity.Region;
import com.example.hangat.map.repository.DataSourceRepository;
import com.example.hangat.map.repository.RegionRepository;
import com.example.hangat.map.service.PlaceNameNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 기상청 예보를 weather_forecasts(17.0)에 적재한다 - 담당 정동현.
 *
 * <p><b>무엇을 넣나</b>: 권역 4곳 × 날짜별 DAILY 행.
 * 단기예보(D+0~3)는 권역 대표 격자(regions.kma_grid_x/y)로 권역마다 따로 부르고,
 * 중기예보(D+4~7)는 기상청이 제주도 단위로만 발표하므로 한 번 받아 네 권역에 같은 값을 넣는다(출처 KMA_MID로 구분).
 * 중기예보는 발표 시각에 따라 시작 날짜가 다르다(2026-09 실측: 06시 발표분은 발표일 +4부터, 18시 발표분은 +5부터).
 * 18시 발표분으로 D+4가 비면 같은 날 06시 발표분에서 채운다 - 스케줄(03:30·06:30)만 돌면 원래 비지 않지만
 * 저녁 기동 적재·수동 실행이 D+4를 '정보 없음'으로 남기지 않게.
 *
 * <p><b>발표 버전</b>: base_at은 기상청 발표 시각(UTC)이다. 같은 발표분을 다시 돌리면 값만 갱신하고(id 보존),
 * 다른 발표분은 이력으로 남는다 - 코스가 저장될 때 본 예보와 최신 예보를 비교하는 근거다.
 *
 * <p><b>실패 격리</b>: 한 권역의 단기예보가 실패해도 나머지 권역과 중기예보는 저장한다. 받지 못한 권역·날짜의
 * 기존 행은 손대지 않는다 - 실패가 데이터 손실로 번지지 않게.
 */
@Service
public class WeatherIngestService {

    private static final Logger log = LoggerFactory.getLogger(WeatherIngestService.class);

    static final String SOURCE_SHORT = "KMA_SHORT";
    static final String SOURCE_MID = "KMA_MID";

    /** 단기예보로 채우는 날짜 수(D+0~3). 그 뒤는 중기예보 몫이다. */
    static final int SHORT_TERM_DAYS = 4;
    /** 중기예보 필드가 있는 발표일 기준 오프셋. 3일째는 단기가 덮으므로 4부터. */
    private static final int MID_FROM = 4;
    private static final int MID_TO = 7;

    private final WeatherClient client;
    private final RegionRepository regionRepository;
    private final DataSourceRepository dataSourceRepository;
    private final WeatherIngestWriter writer;

    public WeatherIngestService(WeatherClient client,
                                RegionRepository regionRepository,
                                DataSourceRepository dataSourceRepository,
                                WeatherIngestWriter writer) {
        this.client = client;
        this.regionRepository = regionRepository;
        this.dataSourceRepository = dataSourceRepository;
        this.writer = writer;
    }

    /**
     * 적재 결과. 실패 건수와 발표 시각을 같이 돌려주는 것이 핵심이다 - 저장 자체는 성공하면서 한 권역이 조용히
     * 빠질 수 있으므로, 이 수치가 없으면 "왜 서부만 날씨가 없지"를 한참 뒤에야 알게 된다.
     */
    public record WeatherIngestResult(int regions, int shortRows, int midRows, int inserted, int updated,
                                      int shortFailures, boolean midFailed,
                                      String shortIssuedAtKst, String midIssuedAtKst) {
    }

    public WeatherIngestResult ingest() {
        return ingest(LocalDateTime.now(KmaIssueTimes.KST));
    }

    /** 시각을 받는 버전 - 테스트가 발표분 선택과 날짜 계산을 고정하려고 쓴다. */
    WeatherIngestResult ingest(LocalDateTime nowKst) {
        List<Region> regions = regionRepository.findAll().stream()
                .filter(Region::isActive)
                .filter(region -> region.getKmaGridX() != null && region.getKmaGridY() != null)
                .sorted(Comparator.comparing(Region::getDisplayOrder))
                .toList();
        if (regions.isEmpty()) {
            log.warn("기상청 격자가 있는 권역이 없어 날씨 적재를 건너뛴다 - 마스터 초기화(regions.kma_grid_x/y)를 확인할 것");
            return new WeatherIngestResult(0, 0, 0, 0, 0, 0, false, null, null);
        }
        DataSource shortSource = source(SOURCE_SHORT);
        DataSource midSource = source(SOURCE_MID);

        LocalDate today = nowKst.toLocalDate();
        Issue shortIssue = KmaIssueTimes.shortTermFor(nowKst);
        Issue midIssue = KmaIssueTimes.midFor(nowKst);

        // 단기: 권역별 격자 호출. 한 권역 실패가 나머지를 막지 않는다
        List<WeatherForecast> shortRows = new ArrayList<>();
        int shortFailures = 0;
        for (Region region : regions) {
            try {
                List<ShortTermItem> items = client.fetchShortTerm(
                        shortIssue.baseDate(), shortIssue.baseTime(), region.getKmaGridX(), region.getKmaGridY());
                for (int offset = 0; offset < SHORT_TERM_DAYS; offset++) {
                    LocalDate day = today.plusDays(offset);
                    DailySummary summary = WeatherDailySummarizer.fromShortTerm(day, items);
                    if (summary.isEmpty()) {
                        continue;   // 그날 자료가 없으면 행을 만들지 않는다
                    }
                    shortRows.add(row(region, shortSource, day, shortIssue, summary));
                }
            } catch (BaseException e) {
                shortFailures++;
                log.warn("단기예보 수집 실패 region={} grid={}/{} issue={} - {}",
                        region.getCode(), region.getKmaGridX(), region.getKmaGridY(), shortIssue.tmFc(), e.getMessage());
            }
        }

        // 중기: 제주도 단위 한 번 호출 → 전 권역 같은 값. 단기가 덮는 날짜(D+0~3)는 단기만 남긴다
        List<WeatherForecast> midRows = new ArrayList<>();
        boolean midFailed = false;
        LocalDate firstMidDay = today.plusDays(SHORT_TERM_DAYS);
        Set<LocalDate> midDaysCovered = new HashSet<>();
        try {
            MidTaItem ta = client.fetchMidTemperature(midIssue.tmFc());
            MidLandItem land = client.fetchMidLand(midIssue.tmFc());
            for (int offset = MID_FROM; offset <= MID_TO; offset++) {
                LocalDate day = midIssue.issueDate().plusDays(offset);
                if (day.isBefore(firstMidDay)) {
                    continue;
                }
                DailySummary summary = WeatherDailySummarizer.fromMid(day, midIssue.issueDate(), ta, land);
                if (summary.isEmpty()) {
                    continue;
                }
                for (Region region : regions) {
                    midRows.add(row(region, midSource, day, midIssue, summary));
                }
                midDaysCovered.add(day);
            }
        } catch (BaseException e) {
            midFailed = true;
            log.warn("중기예보 수집 실패 issue={} - {}", midIssue.tmFc(), e.getMessage());
        }
        // 18시 발표분은 +5부터라 그 발표일 +4(= 오늘 D+4)가 빈다 → 같은 날 06시 발표분으로 보충
        if (!midFailed && !midDaysCovered.contains(firstMidDay)
                && midIssue.issuedAtKst().getHour() == 18
                && firstMidDay.equals(midIssue.issueDate().plusDays(MID_FROM))) {
            Issue morning = new Issue(midIssue.issueDate().atTime(6, 0));
            try {
                DailySummary summary = WeatherDailySummarizer.fromMid(firstMidDay, morning.issueDate(),
                        client.fetchMidTemperature(morning.tmFc()), client.fetchMidLand(morning.tmFc()));
                if (!summary.isEmpty()) {
                    for (Region region : regions) {
                        midRows.add(row(region, midSource, firstMidDay, morning, summary));
                    }
                }
            } catch (BaseException e) {
                log.warn("D+4 보충용 06시 중기 발표분 수집 실패 issue={} - D+4는 '정보 없음'으로 남는다: {}",
                        morning.tmFc(), e.getMessage());
            }
        }

        // 발표분(base_at)별로 upsert - 보충 행은 06시 발표분이라 18시 발표분과 버전이 다르다
        Map<LocalDateTime, List<WeatherForecast>> byIssue = new LinkedHashMap<>();
        for (WeatherForecast row : shortRows) {
            byIssue.computeIfAbsent(row.getBaseAt(), k -> new ArrayList<>()).add(row);
        }
        for (WeatherForecast row : midRows) {
            byIssue.computeIfAbsent(row.getBaseAt(), k -> new ArrayList<>()).add(row);
        }
        int inserted = 0;
        int updated = 0;
        for (Map.Entry<LocalDateTime, List<WeatherForecast>> entry : byIssue.entrySet()) {
            WeatherIngestWriter.Upserted upserted = writer.upsertVersion(entry.getKey(), entry.getValue());
            inserted += upserted.inserted();
            updated += upserted.updated();
        }

        WeatherIngestResult result = new WeatherIngestResult(regions.size(), shortRows.size(), midRows.size(),
                inserted, updated, shortFailures, midFailed, shortIssue.tmFc(), midIssue.tmFc());
        log.info("날씨 적재 완료 {}", result);
        if (shortFailures > 0 || midFailed) {
            log.warn("날씨 적재 일부 실패 - 단기 실패 권역 {}곳, 중기 실패 {} (빠진 날짜·권역은 화면에서 '정보 없음')",
                    shortFailures, midFailed);
        }
        return result;
    }

    private WeatherForecast row(Region region, DataSource source, LocalDate day, Issue issue, DailySummary summary) {
        return WeatherForecast.daily(
                region, source,
                PlaceNameNormalizer.jejuDayToUtc(day), issue.issuedAtUtc(),
                summary.sky(), summary.precipitationType(),
                inRange(summary.tempMin(), -50, 50),
                inRange(summary.tempMax(), -50, 50),
                inRange(summary.rainProb(), 0, 100));
    }

    /** 범위 밖 값은 NULL - 깨진 한 값이 행 전체를 막지 않게, 그렇다고 그럴듯한 값으로 남기지도 않게. */
    private static Integer inRange(Integer value, int min, int max) {
        return value == null || value < min || value > max ? null : value;
    }

    private DataSource source(String code) {
        return dataSourceRepository.findById(code)
                .orElseThrow(() -> new IllegalStateException("data_sources에 '" + code + "' 행이 없다 - MapMasterDataInitializer 확인"));
    }
}
