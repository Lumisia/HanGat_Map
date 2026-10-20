package com.example.hangat.course.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Getter
@NoArgsConstructor
@com.fasterxml.jackson.annotation.JsonIgnoreProperties("budget_total")
public class CourseRequestDto {

    @JsonProperty("start_date")
    private LocalDate startDate;

    @JsonProperty("end_date")
    private LocalDate endDate;

    private Integer people;


    @JsonProperty("course_regions")
    private List<CourseRegionDto> courseRegions;

    @NotNull(message = "이동수단을 선택해주세요.")
    private Transport transport;

    @JsonProperty("course_styles")
    @NotEmpty(message = "여행 스타일을 하나 이상 선택해주세요.")
    @Valid
    private List<@NotNull(message = "여행 스타일 정보는 null일 수 없습니다.") CourseStyleDto>
            courseStyles;

    @JsonProperty("course_place_preferences")
    private List<PlacePreferenceDto> coursePlacePreferences;

    private AccommodationDto accommodation;

    /** Same conditions, explicitly requesting a different composition. */
    private boolean regenerate;
}
