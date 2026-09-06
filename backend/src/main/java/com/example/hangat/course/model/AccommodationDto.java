package com.example.hangat.course.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.example.hangat.map.model.entity.Place;
import com.example.hangat.map.model.entity.PlaceSourceMapping;
import com.example.hangat.map.goodprice.KakaoLocalClient.KakaoPlace;
import com.example.hangat.map.model.entity.Region;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class AccommodationDto {

    @Positive
    @JsonProperty("place_id")
    private Long placeId;

    @NotBlank
    @Pattern(regexp = "KTO|KAKAO_LOCAL")
    @JsonProperty("source_code")
    private String sourceCode;

    @NotBlank
    @Size(max = 100)
    @JsonProperty("source_place_id")
    private String sourcePlaceId;

    @NotBlank
    @Size(max = 200)
    @JsonProperty("place_name")
    private String placeName;

    @Size(max = 300)
    private String address;

    @Size(max = 300)
    @JsonProperty("road_address")
    private String roadAddress;

    @DecimalMin("-90.0")
    @DecimalMax("90.0")
    private Double latitude;

    @DecimalMin("-180.0")
    @DecimalMax("180.0")
    private Double longitude;

    @Size(max = 30)
    private String phone;

    @Size(max = 1000)
    @JsonProperty("place_url")
    private String placeUrl;

    @Size(max = 500)
    @JsonProperty("category_name")
    private String categoryName;

    @Pattern(regexp = "EAST|WEST|SOUTH|NORTH")
    private String region;

    @Size(max = 500)
    @JsonProperty("image_url")
    private String imageUrl;

    public static AccommodationDto from(PlaceSourceMapping mapping) {
        if (mapping == null) {
            return null;
        }
        Place place = mapping.getPlace();
        return new AccommodationDto(
                place.getId(),
                mapping.getSource().getCode(),
                mapping.getSourcePlaceId(),
                place.getName(),
                place.getLotAddress(),
                place.getRoadAddress(),
                place.getLatitude() == null ? null : place.getLatitude().doubleValue(),
                place.getLongitude() == null ? null : place.getLongitude().doubleValue(),
                place.getPhone(),
                null,
                place.getPrimaryCategory().getName(),
                place.getRegion().getCode(),
                place.getImageUrl());
    }

    public static AccommodationDto fromKakao(KakaoPlace place, Region region) {
        return new AccommodationDto(
                null, "KAKAO_LOCAL", place.id(), place.name(), place.address(),
                place.roadAddress(), place.latitude().doubleValue(), place.longitude().doubleValue(),
                place.phone(), place.placeUrl(), place.categoryName(), region.getCode(), null);
    }
}
