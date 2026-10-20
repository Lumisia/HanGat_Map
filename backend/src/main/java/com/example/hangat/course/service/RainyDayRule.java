package com.example.hangat.course.service;

/**
 * 비 예보일 판정 - 새벽 기성 코스 배치, AI 생성 프롬프트, 규칙 기반 폴백이 <b>같은 임계값</b>을 쓴다.
 *
 * <p>임계값을 한 곳에 두는 이유: 배치 코스는 60%부터 실내 위주로 담는데 AI 코스는 70%부터 담으면
 * 같은 날 두 화면이 다른 말을 한다. 값을 바꿀 일이 생기면 여기 하나만 바꾼다.
 *
 * <p>정직성: 실내 여부는 이름 키워드 휴리스틱({@link IndoorClassifier})이라 화면 문구는
 * "실내 위주로 담았어요"까지만 약속한다. 개별 장소의 실내 보장이나 시간대별 비 예보를 말하지 않는다.
 */
public final class RainyDayRule {

    /** 기상청 강수확률(%)이 이 값 이상이면 그날은 비 예보일이다. */
    public static final int PROB_FROM = 60;

    /** 비 예보일에 실내 장소를 골랐을 때 쓰는 추천 사유. 배치 코스·폴백·화면 배지가 같은 문장을 쓴다. */
    public static final String INDOOR_REASON = "비 예보가 있어 실내 위주로 담았어요";

    private RainyDayRule() {
    }

    /** 강수확률을 모르면(null) 비로 단정하지 않는다 - 모르는 것을 경고로 바꾸지 않는다. */
    public static boolean isRainy(Integer rainProbabilityPercent) {
        return rainProbabilityPercent != null && rainProbabilityPercent >= PROB_FROM;
    }
}
