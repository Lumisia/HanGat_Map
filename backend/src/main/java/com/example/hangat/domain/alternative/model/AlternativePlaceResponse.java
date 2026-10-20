package com.example.hangat.domain.alternative.model;

import com.example.hangat.map.model.entity.Place;
import com.example.hangat.map.model.enums.CongestionLevel;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 과밀 스팟의 대안 장소 한 곳 (#과밀지역우회) - 스왑 시트의 카드 한 장.
 *
 * <p><b>snake_case인 이유</b>: 코스 도메인 API(POST /courses, claim)가 전부
 * {@code @JsonNaming(SnakeCase)}이고 프론트 {@code AlternativePlace} 타입도 그 표기를 쓴다.
 * 스왑 시트가 코스 결과 화면 안에 들어가므로 코스 계약 쪽에 맞춘다
 * (메인·날씨 등 다른 내 API는 camelCase 그대로).
 *
 * <p>근거 문구가 둘인 이유: {@code recommendationReason}은 "이 장소가 어떤 곳인지"(카드 설명),
 * {@code replacementReason}은 "왜 바꾸면 나은지"(교체 설득)로 화면에서 쓰임이 다르다.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AlternativePlaceResponse(
        Long placeId,
        String placeName,
        String categoryName,
        String regionName,
        String imageUrl,
        String overview,
        /** 미터 단위 - 프론트가 "1.2km"/"800m"를 스스로 포맷한다. */
        int distanceM,
        double congestionRate,
        CongestionLevel congestionLevel,
        String congestionLabel,
        /** 이 후보가 몇 km 반경에서 나왔는지(10 또는 20) - 화면이 "조금 더 먼 대안" 안내를 띄우는 근거. */
        int radiusKm,
        String recommendationReason,
        String replacementReason
) {

    public static AlternativePlaceResponse of(Place place, double rate, CongestionLevel level,
                                              double distanceKm, int radiusKm,
                                              String recommendationReason, String replacementReason) {
        return new AlternativePlaceResponse(
                place.getId(),
                place.getName(),
                place.getPrimaryCategory().getName(),
                place.getRegion().getName(),
                place.getImageUrl(),
                place.getOverview(),
                (int) Math.round(distanceKm * 1000),
                rate,
                level,
                level.label(),
                radiusKm,
                recommendationReason,
                replacementReason
        );
    }
}
