package com.example.hangat.map.model;

import com.example.hangat.map.model.entity.Place;
import com.example.hangat.map.model.entity.PlaceCategory;
import com.example.hangat.map.model.entity.Region;
import com.example.hangat.map.model.enums.BusinessStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재적재가 좌표를 덮어쓰는 규칙 - 원천이 좌표를 비워 보내면(손상값을 걸러 null) 기존 좌표를 지킨다.
 * 배경: 영주산(KTO 2704351)은 원천 경도가 깨져 있어 V6 로 손보정했는데, 이 규칙이 없으면 다음 재적재가 NULL 로 되돌린다.
 */
class PlaceCoordinateUpdateTest {

    private static final Region WEST = Region.builder().code("WEST").name("서부").displayOrder((byte) 1).build();
    private static final PlaceCategory TOURIST = PlaceCategory.builder().code("TOURIST").name("관광지").build();

    private static Place placeAt(String lat, String lng) {
        return Place.builder()
                .region(WEST).primaryCategory(TOURIST)
                .name("영주산").normalizedName("영주산")
                .latitude(lat == null ? null : new BigDecimal(lat))
                .longitude(lng == null ? null : new BigDecimal(lng))
                .businessStatus(BusinessStatus.UNKNOWN).isGoodPrice(false)
                .build();
    }

    @Test
    void keepsExistingCoordinatesWhenSourceSendsNone() {
        Place place = placeAt("33.4042094", "126.7973723");

        place.updateFromSource(WEST, TOURIST, "영주산", "영주산", "제주 서귀포시 표선면", null, null, null, null);

        assertThat(place.getLatitude()).isEqualByComparingTo("33.4042094");
        assertThat(place.getLongitude()).isEqualByComparingTo("126.7973723");
    }

    @Test
    void ignoresHalfCoordinatesInsteadOfMixingOldAndNew() {
        Place place = placeAt("33.4042094", "126.7973723");

        // 위도만 오고 경도는 걸러진 경우 - 옛 경도에 새 위도를 섞지 않는다
        place.updateFromSource(WEST, TOURIST, "영주산", "영주산", null, new BigDecimal("33.5"), null, null, null);

        assertThat(place.getLatitude()).isEqualByComparingTo("33.4042094");
        assertThat(place.getLongitude()).isEqualByComparingTo("126.7973723");
    }

    @Test
    void replacesCoordinatesWhenSourceSendsBoth() {
        Place place = placeAt("33.4042094", "126.7973723");

        place.updateFromSource(WEST, TOURIST, "영주산", "영주산", null, new BigDecimal("33.4046235"), new BigDecimal("126.7962598"), null, null);

        assertThat(place.getLatitude()).isEqualByComparingTo("33.4046235");
        assertThat(place.getLongitude()).isEqualByComparingTo("126.7962598");
    }

    @Test
    void stillFillsCoordinatesForPlacesThatNeverHadAny() {
        Place place = placeAt(null, null);

        place.updateFromSource(WEST, TOURIST, "영주산", "영주산", null, new BigDecimal("33.4"), new BigDecimal("126.8"), null, null);

        assertThat(place.getLatitude()).isEqualByComparingTo("33.4");
        assertThat(place.getLongitude()).isEqualByComparingTo("126.8");
    }
}
