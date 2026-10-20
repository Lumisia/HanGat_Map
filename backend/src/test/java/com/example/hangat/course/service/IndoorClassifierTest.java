package com.example.hangat.course.service;

import com.example.hangat.map.model.entity.Place;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IndoorClassifierTest {

    @Test
    void 박물관_카페_전시는_실내다() {
        assertThat(IndoorClassifier.isIndoor(named("제주민속자연사박물관"))).isTrue();
        assertThat(IndoorClassifier.isIndoor(named("산굼부리 카페"))).isTrue();
        assertThat(IndoorClassifier.isIndoor(named("빛의 벙커 전시관"))).isTrue();
    }

    @Test
    void 오름_해변_폭포는_실외다() {
        assertThat(IndoorClassifier.isIndoor(named("성산일출봉"))).isFalse();
        assertThat(IndoorClassifier.isIndoor(named("협재해수욕장"))).isFalse();
        assertThat(IndoorClassifier.isIndoor(named("천지연폭포"))).isFalse();
    }

    @Test
    void 이름이_없으면_실외로_본다() {
        // 공공 API는 빈 필드가 흔하다 - NPE 없이 보수적으로(비 맞을 위험 쪽은 실외 취급) 처리
        assertThat(IndoorClassifier.isIndoor(Place.builder()
                .normalizedName("이름없음").isGoodPrice(false).isHiddenGem(false).reviewCount(0)
                .build())).isFalse();
    }

    @Test
    void 이름만으로도_같은_키워드로_판정한다() {
        // AI 후보 사실(PlaceFact)과 응답 DTO는 Place 엔티티가 없고 이름만 있다 - 배치 코스와 같은 목록으로 판정해야 한다
        assertThat(IndoorClassifier.isIndoor("제주민속자연사박물관")).isTrue();
        assertThat(IndoorClassifier.isIndoor("성산일출봉")).isFalse();
        assertThat(IndoorClassifier.isIndoor((String) null)).isFalse();
        assertThat(IndoorClassifier.isIndoor((Place) null)).isFalse();
        assertThat(IndoorClassifier.isIndoor(named("빛의 벙커 전시관")))
                .isEqualTo(IndoorClassifier.isIndoor("빛의 벙커 전시관"));
    }

    @Test
    void 온천_수족관_체험관도_실내다() {
        assertThat(IndoorClassifier.isIndoor(named("산방산탄산온천"))).isTrue();
        assertThat(IndoorClassifier.isIndoor(named("아쿠아플라넷 수족관"))).isTrue();
        assertThat(IndoorClassifier.isIndoor(named("초콜릿 체험관"))).isTrue();
    }

    private Place named(String name) {
        return Place.builder()
                .name(name).normalizedName(name)
                .isGoodPrice(false).isHiddenGem(false).reviewCount(0)
                .build();
    }
}
