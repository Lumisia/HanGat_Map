package com.example.hangat.map.place;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** KTO 콘텐츠 타입 + 세부분류 → 우리 카테고리 (2026-09-14: 음식점 중 카페·찻집은 CAFE) */
class PlaceIngestServiceCategoryTest {

    @Test
    void 음식점은_FOOD_이지만_세부분류가_카페나_찻집이면_CAFE_다() {
        assertThat(PlaceIngestService.categoryFor("39", "FD050100")).isEqualTo("CAFE");   // 카페
        assertThat(PlaceIngestService.categoryFor("39", "FD050200")).isEqualTo("CAFE");   // 찻집
        assertThat(PlaceIngestService.categoryFor("39", "FD040100")).isEqualTo("FOOD");   // 한식
        assertThat(PlaceIngestService.categoryFor("39", null)).isEqualTo("FOOD");         // 세부분류 없음
    }

    @Test
    void 카페_세부분류는_음식점이_아닌_타입엔_영향이_없고_매핑_없는_타입은_null_이다() {
        assertThat(PlaceIngestService.categoryFor("12", "FD050100")).isEqualTo("TOURIST");
        assertThat(PlaceIngestService.categoryFor("32", null)).isEqualTo("LODGING");
        assertThat(PlaceIngestService.categoryFor("25", null)).isNull();
    }
}
