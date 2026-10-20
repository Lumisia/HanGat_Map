package com.example.hangat.domain.weather;

import com.example.hangat.domain.weather.model.ShortTermItem;
import com.example.hangat.domain.weather.model.enums.PrecipitationType;
import com.example.hangat.map.repository.RegionRepository;
import com.example.hangat.notification.repository.weather.TripWeatherSnapshotRepository;
import com.example.hangat.notification.service.support.TripNotificationJsonService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 기존 일별 요약과 별도로 최신 시간별 강수 예보를 수집한다.
 * 외부 API 호출 중에는 DB 잠금을 잡지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TripWeatherIngestService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HHmm");

    private final WeatherClient client;
    private final RegionRepository regions;
    private final TripWeatherSnapshotRepository snapshots;
    private final TripNotificationJsonService json;

    @Value("${hangat.trip-notifications.enabled:false}")
    private boolean enabled;

    /** 실패 권역 수를 반환한다. 성공 권역 데이터는 보존한다. */
    public int ingest() {
        if (!enabled) {
            return 0;
        }

        LocalDateTime now = LocalDateTime.now(KST);
        LocalDateTime issue = latestIssue(now);
        int failures = 0;

        var targets = regions.findAll().stream()
                .filter(region -> region.isActive())
                .filter(region -> region.getKmaGridX() != null)
                .filter(region -> region.getKmaGridY() != null)
                .toList();

        if (targets.isEmpty()) {
            throw new IllegalStateException("알림용 날씨 권역이 없습니다.");
        }

        for (var region : targets) {
            try {
                List<ShortTermItem> items =
                        client.fetchShortTermForNotifications(
                                issue.format(DATE),
                                issue.format(TIME),
                                region.getKmaGridX(),
                                region.getKmaGridY()
                        );

                Map<String, String> values = new TreeMap<>();

                for (ShortTermItem item : items) {
                    if (!"PTY".equals(item.category())) {
                        continue;
                    }

                    PrecipitationType type =
                            PrecipitationType.fromKmaPty(item.fcstValue());

                    if (type == null || type == PrecipitationType.UNKNOWN) {
                        continue;
                    }

                    LocalDate date = LocalDate.parse(item.fcstDate(), DATE);
                    String time = item.fcstTime();

                    LocalDateTime target = "2400".equals(time)
                            ? date.plusDays(1).atStartOfDay()
                            : date.atTime(LocalTime.parse(time, TIME));

                    if (target.isBefore(now)) {
                        continue;
                    }

                    // 비와 소나기의 표현 차이만으로 반복 알림을 만들지 않는다.
                    String value = type == PrecipitationType.SHOWER
                            ? PrecipitationType.RAIN.name()
                            : type.name();

                    values.put(toUtc(target).toString(), value);
                }

                if (values.isEmpty()) {
                    throw new IllegalStateException("비교 가능한 예보가 없습니다.");
                }

                snapshots.save(
                        region.getId(),
                        toUtc(issue),
                        LocalDateTime.now(ZoneOffset.UTC),
                        json.write(values)
                );

            } catch (RuntimeException e) {
                failures++;
                // 요청 URL에는 서비스 키가 들어갈 수 있어 그대로 기록하지 않는다.
                log.warn("알림용 날씨 수집 실패 region={} error={}",
                        region.getId(), e.getClass().getSimpleName());
            }
        }

        return failures;
    }

    /**
     * 기존 KmaIssueTimes의 05시 고정 규칙은 건드리지 않는다.
     * API 반영 지연을 고려해 발표 후 1시간이 지난 최신 발표분을 선택한다.
     */
    private LocalDateTime latestIssue(LocalDateTime now) {
        LocalDateTime available = now.minusHours(1);

        for (int offset = 0; offset <= 1; offset++) {
            LocalDate day = available.toLocalDate().minusDays(offset);

            for (int hour : new int[]{23, 20, 17, 14, 11, 8, 5, 2}) {
                LocalDateTime candidate = day.atTime(hour, 0);

                if (!candidate.isAfter(available)) {
                    return candidate;
                }
            }
        }

        throw new IllegalStateException("기상청 발표 시각을 선택하지 못했습니다.");
    }

    private LocalDateTime toUtc(LocalDateTime value) {
        return value.atZone(KST)
                .withZoneSameInstant(ZoneOffset.UTC)
                .toLocalDateTime();
    }
}
