package com.example.hangat.map.hiddengem;

import com.example.hangat.map.model.entity.Place;
import com.example.hangat.map.model.entity.PlaceCategory;
import com.example.hangat.map.model.entity.Region;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 숨은 명소 산식(HG_V1) - 심사 설명서에 공개하는 규칙이라 경계를 하나씩 못 박는다.
 * "TourAPI 등재(관광지 카테고리) ∧ 콘텐츠 품질 0.7 이상 ∧ 관광공사 집계 대상 아님".
 */
class HiddenGemRuleTest {

    private final HiddenGemRule rule = new HiddenGemRule();
    private final Region east = Region.builder().code("EAST").name("동부").displayOrder((byte) 1).build();
    private final PlaceCategory tourist = PlaceCategory.builder().code("TOURIST").name("관광지").build();
    private final PlaceCategory food = PlaceCategory.builder().code("FOOD").name("음식점").build();

    private Place.PlaceBuilder tourist(String name) {
        return Place.builder().region(east).primaryCategory(tourist).name(name).normalizedName(name);
    }

    @Test
    void 사진_좌표_주소에_운영시간까지_있고_집계_대상이_아니면_숨은_명소다() {
        Place place = tourist("가문이오름")
                .imageUrl("https://tong.visitkorea.or.kr/x.jpg")
                .latitude(new BigDecimal("33.4")).longitude(new BigDecimal("126.7"))
                .roadAddress("제주특별자치도 제주시 구좌읍")
                .operatingHoursText("상시 개방")
                .build();

        HiddenGemRule.Verdict verdict = rule.evaluate(place, false);

        assertThat(verdict.score()).isEqualByComparingTo("0.700");
        assertThat(verdict.hiddenGem()).isTrue();
    }

    @Test
    void 사진만_있으면_품질_미달이다() {
        // 사진 0.30 + 좌표 0.10 + 주소 0.10 = 0.50 < 0.70 - 소개할 정보가 하나는 더 있어야 한다
        Place place = tourist("이름 없는 오름")
                .imageUrl("https://tong.visitkorea.or.kr/x.jpg")
                .latitude(new BigDecimal("33.4")).longitude(new BigDecimal("126.7"))
                .roadAddress("제주특별자치도 제주시")
                .build();

        HiddenGemRule.Verdict verdict = rule.evaluate(place, false);

        assertThat(verdict.score()).isEqualByComparingTo("0.500");
        assertThat(verdict.hiddenGem()).isFalse();
        assertThat(verdict.reason()).isEqualTo("low-quality");
    }

    @Test
    void 집계_대상이면_품질이_높아도_숨은_명소가_아니다_점수는_남긴다() {
        Place place = tourist("성산일출봉")
                .imageUrl("https://tong.visitkorea.or.kr/x.jpg")
                .latitude(new BigDecimal("33.4")).longitude(new BigDecimal("126.9"))
                .roadAddress("서귀포시 성산읍").operatingHoursText("07:00-20:00").overview("유네스코 세계자연유산")
                .reviewCount(3)
                .build();

        HiddenGemRule.Verdict verdict = rule.evaluate(place, true);

        assertThat(verdict.hiddenGem()).isFalse();
        assertThat(verdict.reason()).isEqualTo("famous");
        assertThat(verdict.score()).isEqualByComparingTo("1.000");
    }

    @Test
    void 폐업이면_제외하고_음식점은_명소가_아니다() {
        Place closed = tourist("문 닫은 곳")
                .imageUrl("x").latitude(BigDecimal.ONE).longitude(BigDecimal.ONE).roadAddress("주소").overview("소개")
                .build();
        closed.markClosed();
        Place restaurant = Place.builder().region(east).primaryCategory(food).name("식당").normalizedName("식당")
                .imageUrl("x").latitude(BigDecimal.ONE).longitude(BigDecimal.ONE).roadAddress("주소").overview("소개")
                .build();

        assertThat(rule.evaluate(closed, false).reason()).isEqualTo("closed");
        assertThat(rule.evaluate(restaurant, false).reason()).isEqualTo("not-tourist");
    }

    @Test
    void 착한가격_메뉴_문단은_소개글로_치지_않는다() {
        // 착한가격 배치가 overview 에 "대표메뉴:..." 를 넣는다 - 메뉴판이 관광지 소개는 아니다
        Place place = tourist("메뉴만").imageUrl("x").latitude(BigDecimal.ONE).longitude(BigDecimal.ONE)
                .roadAddress("주소").overview(Place.GOOD_PRICE_MENU_PREFIX + " 갈치조림 12,000원").build();

        assertThat(rule.evaluate(place, false).score()).isEqualByComparingTo("0.500");
    }

    @Test
    void 빈_문자열은_없는_정보로_센다() {
        // 공공 API는 "" 를 자주 준다 - 공백을 사진·주소로 치면 없는 품질을 지어낸다
        Place place = tourist("빈칸").imageUrl("  ").roadAddress("").operatingHoursText("").build();

        assertThat(rule.evaluate(place, false).score()).isEqualByComparingTo("0.000");
    }
}
