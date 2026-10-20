package com.example.hangat.course.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 비 예보 임계값은 배치 코스·AI 프롬프트·폴백이 함께 쓰는 값이라 경계를 못 박는다. */
class RainyDayRuleTest {

    @Test
    void 강수확률_60부터_비_예보일이다() {
        assertThat(RainyDayRule.isRainy(59)).isFalse();
        assertThat(RainyDayRule.isRainy(60)).isTrue();
        assertThat(RainyDayRule.isRainy(100)).isTrue();
    }

    @Test
    void 강수확률을_모르면_비로_단정하지_않는다() {
        // 기상청 결측은 흔하다 - 모르는 날을 비 예보일로 만들어 실외 명소를 빼면 없는 사실로 코스를 바꾸는 것이다
        assertThat(RainyDayRule.isRainy(null)).isFalse();
    }

    @Test
    void 임계값과_문구는_한_곳의_상수다() {
        assertThat(RainyDayRule.PROB_FROM).isEqualTo(60);
        assertThat(RainyDayRule.INDOOR_REASON).isEqualTo("비 예보가 있어 실내 위주로 담았어요");
    }
}
