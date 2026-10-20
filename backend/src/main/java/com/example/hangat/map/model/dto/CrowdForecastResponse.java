package com.example.hangat.map.model.dto;

import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 혼잡 예보 전체 ({@code GET /api/crowd/forecast}) - 설계서 §2.2
 *
 * <p><b>장소 전체 × 예보일을 한 번에 내려준다.</b> 프론트의 {@code rank30}(30일 중 순위)·
 * {@code bestDay}(가장 한산한 날) 계산이 이미 클라이언트에 있어서, 시리즈만 주면
 * 화면 로직을 하나도 안 고쳐도 된다. 날짜를 바꿀 때마다 서버를 부르면 슬라이더가 버벅인다(MAP-02는 0.3초 이내 갱신 요건).
 *
 * <p><b>{@code days}를 응답에 싣는 이유</b>: 실측 예보 일수가 21~22일로 <b>날마다 다르다</b>(§3.5.2).
 * 프론트가 30으로 하드코딩하면 없는 날짜를 읽어 조용히 어긋난다. 화면은 30일 캘린더이므로
 * 남는 날은 '정보 없음'으로 그린다.
 *
 * <p><b>예보 없는 장소는 아예 넣지 않는다.</b> 2,138곳 중 실제로 예보가 있는 건 345곳뿐이라
 * 나머지를 null 배열로 채우면 응답만 커진다. 프론트는 {@code values[id] ?? null}로 읽으면 된다
 * (설계서 §2.2의 "null 배열" 기술을 이렇게 정정한다).
 */
@Getter
public class CrowdForecastResponse {

    /**
     * 발표분을 받아 온 날(제주 기준). <b>{@code from}과 다른 것이 정상이다.</b>
     *
     * <p>적재 배치는 새벽 3시에 도는데 그 시각 관광공사 API는 아직 <b>어제부터</b> 시작하는 창을 준다
     * (2026-09 실측: 03시 응답은 D-1부터, 같은 날 저녁 응답은 D부터). 그래서 정상 적재에서도
     * {@code from}은 늘 어제다. <b>예보가 묵었는지는 창 시작일이 아니라 이 발표일로 판단해야 한다</b> -
     * {@code from}으로 보면 매일 '갱신 대기'가 뜬다.
     */
    private final LocalDate baseDate;

    /** 첫 예보일. <b>제주 기준 날짜</b>다 - DB는 UTC로 저장하지만 화면 계약은 현지 날짜다. */
    private final LocalDate from;

    /** {@code from}부터 며칠치인지. values의 모든 배열 길이가 이 값과 같다. */
    private final int days;

    /**
     * placeId → 날짜순 집중률(0~100). <b>인덱스 i = from + i일</b>.
     *
     * <p>중간에 빠진 날은 null이다 - 0으로 채우면 '가장 한산한 날'로 뽑혀 사람을 잘못 보낸다.
     * 키가 문자열인 이유는 JSON 객체 키가 문자열뿐이라서다.
     */
    private final Map<String, List<BigDecimal>> values;

    public CrowdForecastResponse(LocalDate baseDate, LocalDate from, int days,
                                 Map<String, List<BigDecimal>> values) {
        this.baseDate = baseDate;
        this.from = from;
        this.days = days;
        this.values = values;
    }

    /** 예보가 한 건도 없을 때. 화면 전체가 '정보 없음'이 된다 - 빈 응답이 오류는 아니다. */
    public static CrowdForecastResponse empty() {
        return new CrowdForecastResponse(null, null, 0, Map.of());
    }
}
