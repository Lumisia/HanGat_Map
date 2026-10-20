package com.example.hangat.map.model;

import com.example.hangat.map.model.entity.Place;
import com.example.hangat.map.model.entity.PlaceCategory;
import com.example.hangat.map.model.entity.Region;
import com.example.hangat.map.model.enums.BusinessStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 착한가격 배치가 overview 를 다루는 규칙과 폐업 표시 규칙.
 * 우리가 넣은 "대표메뉴:" 문단만 덮어쓰고 지운다 - KTO 소개글은 건드리지 않는다.
 */
class PlaceGoodPriceTextTest {

    private static final Region WEST = Region.builder().code("WEST").name("서부").displayOrder((byte) 1).build();
    private static final PlaceCategory FOOD = PlaceCategory.builder().code("FOOD").name("음식점").build();
    private static final LocalDate BASE = LocalDate.of(2026, 6, 30);

    private static Place placeWith(String overview) {
        return Place.builder()
                .region(WEST).primaryCategory(FOOD)
                .name("신미국밥").normalizedName("신미국밥")
                .overview(overview)
                .businessStatus(BusinessStatus.UNKNOWN).isGoodPrice(false)
                .build();
    }

    @Test
    void 빈_overview에는_메뉴_문단을_쓴다() {
        Place place = placeWith(null);
        place.markGoodPrice(BASE, "대표메뉴: 순대국밥 9,000원", null);
        assertThat(place.isGoodPrice()).isTrue();
        assertThat(place.getGoodPriceBaseDate()).isEqualTo(BASE);
        assertThat(place.getOverview()).isEqualTo("대표메뉴: 순대국밥 9,000원");
    }

    @Test
    void 우리가_넣은_메뉴_문단은_새_가격으로_덮어쓴다() {
        Place place = placeWith("대표메뉴: 순대국밥 9,000원");
        place.markGoodPrice(BASE.plusMonths(4), "대표메뉴: 순대국밥 10,000원", null);
        assertThat(place.getOverview()).isEqualTo("대표메뉴: 순대국밥 10,000원");
        assertThat(place.getGoodPriceBaseDate()).isEqualTo(BASE.plusMonths(4));
    }

    @Test
    void KTO_소개글이_있으면_건드리지_않는다() {
        Place place = placeWith("제주 김녕에서 30년 된 국밥집이다.");
        place.markGoodPrice(BASE, "대표메뉴: 순대국밥 9,000원", null);
        assertThat(place.isGoodPrice()).isTrue();
        assertThat(place.getOverview()).isEqualTo("제주 김녕에서 30년 된 국밥집이다.");
    }

    @Test
    void 해제하면_메뉴_문단만_지우고_소개글은_남긴다() {
        Place menu = placeWith("대표메뉴: 순대국밥 9,000원");
        menu.markGoodPrice(BASE, null, null);
        menu.clearGoodPrice();
        assertThat(menu.isGoodPrice()).isFalse();
        assertThat(menu.getGoodPriceBaseDate()).isNull();
        assertThat(menu.getOverview()).isNull();

        Place intro = placeWith("제주 김녕에서 30년 된 국밥집이다.");
        intro.clearGoodPrice();
        assertThat(intro.getOverview()).isEqualTo("제주 김녕에서 30년 된 국밥집이다.");
    }

    @Test
    void 폐업_표시는_되돌릴_수_있고_UNKNOWN으로_돌아온다() {
        Place place = placeWith(null);
        place.markClosed();
        assertThat(place.getBusinessStatus()).isEqualTo(BusinessStatus.CLOSED);
        place.reopenIfClosed();
        assertThat(place.getBusinessStatus()).isEqualTo(BusinessStatus.UNKNOWN);
        // 폐업이 아니던 장소는 건드리지 않는다
        place.reopenIfClosed();
        assertThat(place.getBusinessStatus()).isEqualTo(BusinessStatus.UNKNOWN);
    }
}
