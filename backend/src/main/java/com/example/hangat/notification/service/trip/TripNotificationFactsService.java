package com.example.hangat.notification.service.trip;

import com.example.hangat.notification.service.support.TripNotificationJsonService;

import com.example.hangat.course.repository.CourseItemRepository;
import com.example.hangat.domain.weather.model.enums.PrecipitationType;
import com.example.hangat.domain.weather.model.enums.WeatherGranularity;
import com.example.hangat.domain.weather.repository.WeatherForecastRepository;
import com.example.hangat.map.repository.CongestionForecastRepository;
import com.example.hangat.notification.model.TripNotificationSample;
import com.example.hangat.notification.model.entity.TripWeatherSnapshot;
import com.example.hangat.notification.repository.weather.TripWeatherSnapshotRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 코스의 방문 날짜·권역·장소에 해당하는 예보만 조회한다.
 * 이 서비스는 외부 API를 호출하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class TripNotificationFactsService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static final TypeReference<Map<String, String>> WEATHER_TYPE =
            new TypeReference<>() {};

    private final CourseItemRepository items;
    private final WeatherForecastRepository dailyWeather;
    private final CongestionForecastRepository congestion;
    private final TripWeatherSnapshotRepository hourlyWeather;
    private final TripNotificationJsonService json;

    @Transactional(readOnly = true)
    public Map<String, TripNotificationSample> read(
            Long courseId,
            LocalDate start,
            LocalDate end,
            LocalDateTime nowKst
    ) {
        LocalDateTime nowUtc = utc(nowKst);
        Map<String, TripNotificationSample> result = new TreeMap<>();

        Map<Short, TripWeatherSnapshot> snapshots =
                hourlyWeather.findAll().stream().collect(Collectors.toMap(
                        TripWeatherSnapshot::getRegionId,
                        Function.identity()
                ));

        Map<Short, Map<String, String>> parsed = new HashMap<>();
        Set<String> visitedWeatherDays = new HashSet<>();

        LocalDateTime congestionVersion = congestion.findLatestBaseAt()
                .filter(base -> !base.isBefore(
                        nowKst.toLocalDate().minusDays(1).atStartOfDay()
                ))
                .orElse(null);

        for (var item : items.findItemsWithPlace(courseId)) {
            LocalDate date = item.getVisitDate();

            if (date.isBefore(nowKst.toLocalDate())
                    || date.isBefore(start)
                    || date.isAfter(end)) {
                continue;
            }

            var place = item.getPlace();
            var region = place.getRegion();
            Short regionId = region.getId();

            String weatherDay = regionId + ":" + date;

            // 같은 날 같은 권역의 여러 장소는 날씨를 한 번만 비교한다.
            if (visitedWeatherDays.add(weatherDay)) {
                TripWeatherSnapshot snapshot = snapshots.get(regionId);
                boolean hasHourly = false;

                if (snapshot != null
                        && !snapshot.getIssuedAt().isBefore(nowUtc.minusHours(36))) {

                    Map<String, String> values = parsed.computeIfAbsent(
                            regionId,
                            ignored -> json.read(
                                    snapshot.getPayloadJson(), WEATHER_TYPE
                            )
                    );

                    for (var entry : values.entrySet()) {
                        LocalDateTime targetUtc =
                                LocalDateTime.parse(entry.getKey());

                        LocalDateTime targetKst = targetUtc
                                .atOffset(ZoneOffset.UTC)
                                .atZoneSameInstant(KST)
                                .toLocalDateTime();

                        if (!targetKst.toLocalDate().equals(date)
                                || targetUtc.isBefore(nowUtc)) {
                            continue;
                        }

                        hasHourly = true;

                        result.put(
                                "H:" + regionId + ":" + targetUtc,
                                new TripNotificationSample(
                                        "WEATHER",
                                        date,
                                        region.getName() + " 권역 "
                                                + targetKst.getHour() + "시",
                                        entry.getValue(),
                                        snapshot.getIssuedAt()
                                )
                        );
                    }
                }

                // 먼 날짜는 기존 중기예보를 사용하되 시간별 예보와 섞어 비교하지 않는다.
                if (!hasHourly) {
                    dailyWeather
                            .findFirstByRegionIdAndForecastAtAndGranularityOrderByBaseAtDesc(
                                    regionId,
                                    utc(date.atStartOfDay()),
                                    WeatherGranularity.DAILY
                            )
                            .filter(forecast ->
                                    "KMA_MID".equals(forecast.getSource().getCode()))
                            .filter(forecast ->
                                    !forecast.getBaseAt().isBefore(nowUtc.minusHours(36)))
                            .ifPresent(forecast -> {
                                PrecipitationType type =
                                        forecast.getPrecipitationType();

                                if (type == null || type == PrecipitationType.UNKNOWN) {
                                    return;
                                }

                                String value = type == PrecipitationType.SHOWER
                                        ? PrecipitationType.RAIN.name()
                                        : type.name();

                                result.put(
                                        "D:" + regionId + ":" + date,
                                        new TripNotificationSample(
                                                "WEATHER",
                                                date,
                                                "제주 전역 중기예보",
                                                value,
                                                forecast.getBaseAt()
                                        )
                                );
                            });
                }
            }

            if (congestionVersion != null) {
                congestion.findOne(
                        place.getId(),
                        utc(date.atStartOfDay()),
                        congestionVersion
                ).ifPresent(forecast -> result.put(
                        "C:" + place.getId() + ":" + date,
                        new TripNotificationSample(
                                "CONGESTION",
                                date,
                                place.getName(),
                                forecast.getLevel().name(),
                                // 기존 혼잡 base_at은 한국 기준 배치 날짜다.
                                utc(congestionVersion)
                        )
                ));
            }
        }

        return result;
    }

    private LocalDateTime utc(LocalDateTime value) {
        return value.atZone(KST)
                .withZoneSameInstant(ZoneOffset.UTC)
                .toLocalDateTime();
    }
}
