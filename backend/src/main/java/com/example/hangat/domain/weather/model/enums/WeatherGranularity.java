package com.example.hangat.domain.weather.model.enums;

/**
 * 날씨 예보 행의 시간 단위 - weather_forecasts.granularity (테이블 명세서 17.0).
 *
 * <p>1단계(2026-09)는 DAILY만 적재한다. 화면 어디도 시간별 날씨를 보여주지 않고,
 * 기상청 단기예보의 시간별 원자료는 하루 요약(최저·최고·강수확률 최대)으로 접어서 넣는다.
 * HOURLY는 명세서와 스키마를 맞추기 위해 상수만 둔다 - MariaDB 네이티브 ENUM이라
 * 나중에 값을 보태려면 ALTER가 필요하니 처음부터 정의해 둔다.
 */
public enum WeatherGranularity {
    DAILY,
    HOURLY
}
