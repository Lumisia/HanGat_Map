package com.example.hangat.course.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class TourPlaceDto {

    @JsonProperty("contentid")
    private String contentId;

    private String title;

    @JsonProperty("addr1")
    private String address;

    @JsonProperty("mapy")
    private Double latitude;

    @JsonProperty("mapx")
    private Double longitude;

    @JsonProperty("cat1")
    private String category;

    @JsonProperty("cat2")
    private String category2;

    @JsonProperty("cat3")
    private String category3;

    @JsonProperty("lclsSystm3")
    private String classificationCode;

    @JsonProperty("firstimage")
    private String imageUrl;

    /** Internal geometry bridge only; stored facts remain authoritative. */
    public static TourPlaceDto fromStored(com.example.hangat.course.facts.CourseCandidate fact) {
        TourPlaceDto value = new TourPlaceDto();
        value.contentId = fact.identity().candidateId();
        value.title = fact.place().name();
        value.address = fact.place().address();
        value.latitude = fact.place().latitude() == null ? null : fact.place().latitude().doubleValue();
        value.longitude = fact.place().longitude() == null ? null : fact.place().longitude().doubleValue();
        value.imageUrl = fact.place().imageUrl();
        return value;
    }
}
