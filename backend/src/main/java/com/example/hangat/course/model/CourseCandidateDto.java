package com.example.hangat.course.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

@Getter
@RequiredArgsConstructor
public class CourseCandidateDto {

    private final TourPlaceDto place;
    private final List<CongestionDto> congestionData;
    private final PreferenceType preferenceType;
    private final List<String> confirmedStyleHints;

    @com.fasterxml.jackson.annotation.JsonIgnore
    private com.example.hangat.course.facts.CourseCandidate storedCandidate;

    public static CourseCandidateDto fromStored(com.example.hangat.course.facts.CourseCandidate fact) {
        CourseCandidateDto value = new CourseCandidateDto(TourPlaceDto.fromStored(fact), List.of(),
                fact.userConstraint().preferenceType(),
                fact.styleHints().stream().map(com.example.hangat.course.facts.StyleHint::styleCode).toList());
        value.storedCandidate = fact;
        return value;
    }
}
