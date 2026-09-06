package com.example.hangat.domain.weather.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 중기 육상 예보 아이템 - 발표일 기준 +4~+7일은 오전/오후로 나뉘어 온다(+8~+10은 하루 하나).
 * 2026-09 실측: 응답에 +3일 필드(wf3Am/rnSt3Am)는 더 이상 없다 - null로 온다.
 * 하루 요약은 {@code WeatherDailySummarizer}가 오전·오후를 접는다(강수확률은 큰 쪽, 하늘은 비·눈 있는 쪽).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MidLandItem(
        // wf4Am = 맑음/흐림/흐리고 비 같은 텍스트
        String wf3Am,
        String wf4Am,
        String wf5Am,
        String wf6Am,
        String wf7Am,
        // rnSt4Am = 강수 확률(%)
        Integer rnSt3Am,
        Integer rnSt4Am,
        Integer rnSt5Am,
        Integer rnSt6Am,
        Integer rnSt7Am,
        String wf4Pm,
        String wf5Pm,
        String wf6Pm,
        String wf7Pm,
        Integer rnSt4Pm,
        Integer rnSt5Pm,
        Integer rnSt6Pm,
        Integer rnSt7Pm
) {
}
