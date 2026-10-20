package com.example.hangat.batch;

import com.example.hangat.domain.weather.model.enums.WeatherGranularity;
import com.example.hangat.domain.weather.repository.WeatherForecastRepository;
import com.example.hangat.map.repository.CongestionForecastRepository;
import com.example.hangat.map.repository.RegionRepository;
import com.example.hangat.map.service.PlaceNameNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 샘플 코스 배치의 선행 데이터 확인 - 시각만 다르게 예약해도 실행 순서는 보장되지 않는다.
 * 내일 출발하는 최대 3일 코스에 필요한 혼잡도와 활성 권역의 최신 날씨가 없으면 재시도한다.
 */
@Component
@Profile("batch")
@RequiredArgsConstructor
public class BatchPrerequisiteChecker {
    private final CongestionForecastRepository congestion;
    private final WeatherForecastRepository weather;
    private final RegionRepository regions;

    /** 혼잡도 발표 버전은 KST 날짜, 날씨 발표·예보 대상 시각은 기존 저장 규칙인 UTC를 따른다. */
    public void check(LocalDate startDate) {
        var today = startDate.minusDays(1);
        var activeRegions = regions.findAll().stream().filter(region -> region.isActive()).toList();
        if (activeRegions.isEmpty()) {
            throw new IllegalStateException("배치 선행 조건 실패: 활성 권역 마스터가 없습니다.");
        }
        for (int offset = 0; offset < 3; offset++) {
            var forecastAt = PlaceNameNormalizer.jejuDayToUtc(startDate.plusDays(offset));
            if (!congestion.existsByBaseAtAndForecastAt(today.atStartOfDay(), forecastAt)) {
                throw new IllegalStateException("배치 선행 조건 실패: 오늘 적재한 혼잡 예보가 부족합니다.");
            }
            for (var region : activeRegions) {
                var forecast = weather.findFirstByRegionIdAndForecastAtAndGranularityOrderByBaseAtDesc(
                        region.getId(), forecastAt, WeatherGranularity.DAILY);
                if (forecast.isEmpty()
                        || forecast.get().getBaseAt().isBefore(PlaceNameNormalizer.jejuDayToUtc(today))) {
                    throw new IllegalStateException("배치 선행 조건 실패: 최신 날씨 예보가 부족합니다. region="
                            + region.getCode() + ", date=" + startDate.plusDays(offset));
                }
            }
        }
    }
}
