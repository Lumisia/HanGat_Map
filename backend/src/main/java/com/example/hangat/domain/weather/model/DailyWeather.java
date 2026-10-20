package com.example.hangat.domain.weather.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record DailyWeather(
        LocalDate date,
        Integer minTemp,
        Integer maxTemp,
        String sky,
        Integer rainProb,
        /** 기상청 발표 시각(KST). DB 적재분에서만 있고 라이브 폴백·값 없는 날은 null - 화면의 "n/n 05:00 발표" 라벨용 */
        LocalDateTime issuedAt
) {
    /** 발표 시각을 모르는 경로(라이브 폴백·요약기·테스트)용 - 기존 호출부가 그대로 컴파일된다 */
    public DailyWeather(LocalDate date, Integer minTemp, Integer maxTemp, String sky, Integer rainProb) {
        this(date, minTemp, maxTemp, sky, rainProb, null);
    }
}
