package com.example.hangat.favorite.model;

import com.example.hangat.map.detail.DetailFieldMapper;
import com.example.hangat.map.model.entity.Place;
import com.example.hangat.map.model.enums.BusinessStatus;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 찜 목록 한 줄(MY_006) - 장소 요약 + 찜 시각 + 오늘 혼잡.
 * 지도 목록 응답({@code PlaceListResponse})과 같은 이름을 쓰되, 마이페이지 카드가 쓰는
 * 입장료·별점·대표사진·오늘 집중률을 더 싣는다. 상세 전체(overview 등)는 싣지 않는다 - 카드는 요약이다.
 */
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class FavoriteResponse {

    private static final DetailFieldMapper FEE_MAPPER = new DetailFieldMapper();

    private final Long placeId;
    private final String name;
    private final String regionCode;
    private final String regionName;
    private final String categoryCode;
    private final String categoryName;
    /** 세부분류(오름·해수욕장…). 미분류면 null */
    private final String tagName;
    private final String roadAddress;
    private final String lotAddress;
    private final BigDecimal latitude;
    private final BigDecimal longitude;
    private final String phone;
    /** 운영시간 원문. 자유 텍스트라 화면이 그대로 보여준다 */
    private final String operatingHoursText;
    /** 입장료 원문. null = 정보 없음 */
    private final String useFeeText;
    /** '무료'만 뜻할 때 true - 상세와 같은 판정({@link DetailFieldMapper}) */
    private final boolean free;
    private final Boolean parkingAvailable;
    private final Boolean toiletAvailable;
    private final BusinessStatus businessStatus;
    /** 별점 후기가 없으면 null - 0.0 으로 그리지 않는다(상세와 같은 규칙) */
    private final BigDecimal ratingAvg;
    private final int reviewCount;
    /** 대표사진(첫 사진의 썸네일, 없으면 원본). 사진 없는 장소는 null */
    private final String imageUrl;
    /** 오늘(제주 기준) 집중률, 최신 발표분. 예보 없는 장소·날은 null */
    private final BigDecimal crowdRate;
    private final LocalDateTime favoritedAt;

    public static FavoriteResponse from(Favorite favorite, String tagName, String imageUrl, BigDecimal crowdRate) {
        Place place = favorite.getPlace();
        BigDecimal ratingAvg = place.getRatingAvg();
        return FavoriteResponse.builder()
                .placeId(place.getId())
                .name(place.getName())
                .regionCode(place.getRegion().getCode())
                .regionName(place.getRegion().getName())
                .categoryCode(place.getPrimaryCategory().getCode())
                .categoryName(place.getPrimaryCategory().getName())
                .tagName(tagName)
                .roadAddress(place.getRoadAddress())
                .lotAddress(place.getLotAddress())
                .latitude(place.getLatitude())
                .longitude(place.getLongitude())
                .phone(place.getPhone())
                .operatingHoursText(place.getOperatingHoursText())
                .useFeeText(place.getUseFeeText())
                .free(FEE_MAPPER.isFree(place.getUseFeeText()))
                .parkingAvailable(place.getParkingAvailable())
                .toiletAvailable(place.getToiletAvailable())
                .businessStatus(place.getBusinessStatus())
                .ratingAvg(ratingAvg == null || ratingAvg.compareTo(BigDecimal.ZERO) == 0 ? null : ratingAvg)
                .reviewCount(place.getReviewCount())
                .imageUrl(imageUrl)
                .crowdRate(crowdRate)
                .favoritedAt(favorite.getCreatedAt())
                .build();
    }
}
