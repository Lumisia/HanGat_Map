package com.example.hangat.map.model.enums;

import java.math.BigDecimal;

/**
 * 혼잡 단계 - 테이블 명세서 16.0 {@code congestion_forecasts.level}
 *
 * <p>화면의 핀 색과 좌측 순위 목록이 이 값으로 칠해진다(MAP_004).
 *
 * <p><b>왜 전역 고정 임계값인가</b> - 설계서 §3.5, 2026-08-23 제주 전역 9,856행 실측:
 * {@code cnctrRate == 100}인 관광지가 468곳 중 3곳뿐이었다. 즉 100은 "조회 기간 내 최댓값"이
 * 아니라 <b>그 관광지의 과거 전체 최성수기</b>이고, rate 자체가 이미 장소별로 정규화된 값이다.
 * 그래서 기간 분포로 다시 정규화하면 원래 의미가 왜곡된다.
 *
 * <p>⚠️ <b>장소 간 절대 비교는 여전히 불가능하다</b>(§3.5.1). 무명 오름 90이 성산일출봉 40보다
 * 붐벼 보이지만 실제 방문객 수는 반대다. 화면 문구를 "이 관광지 기준"으로 명시해야 한다.
 */
public enum CongestionLevel {

    /** 이 곳 최성수기의 40% 미만. */
    QUIET("한산"),

    NORMAL("보통"),

    /** 이 곳 최성수기의 70% 이상. */
    CROWDED("혼잡");

    private final String label;

    CongestionLevel(String label) {
        this.label = label;
    }

    /**
     * 화면 배지 문구. 팀 합의(2026-08-31)로 혼잡 등급은 이 3단계가 유일한 표준이다 -
     * 백엔드 응답의 levelLabel과 프론트 라벨이 전부 여기서 나온다. 별도 4단계를 만들지 말 것.
     */
    public String label() {
        return label;
    }

    /** 팀 공통 고정 경계값. 조회 결과의 분포로 다시 정규화하지 않는다. */
    private static final BigDecimal QUIET_MAX = new BigDecimal("40");
    private static final BigDecimal CROWDED_MIN = new BigDecimal("70");

    /**
     * 집중률에서 단계를 뽑는다. 수집 시점에 rate와 함께 확정한다(명세서 상세설명).
     *
     * @param rate 0~100. null이면 행 자체를 만들지 않으므로 여기 들어올 일이 없다
     * @throws IllegalArgumentException rate가 null일 때 - 조용히 QUIET로 떨어뜨리면
     *                                  '정보 없음'이 '한산'으로 둔갑한다(명세서 금지 사항)
     */
    public static CongestionLevel from(BigDecimal rate) {
        if (rate == null) {
            throw new IllegalArgumentException("집중률이 없으면 단계를 만들지 않는다 - 행을 건너뛸 것");
        }
        if (rate.compareTo(QUIET_MAX) < 0) {
            return QUIET;
        }
        return rate.compareTo(CROWDED_MIN) < 0 ? NORMAL : CROWDED;
    }
}
