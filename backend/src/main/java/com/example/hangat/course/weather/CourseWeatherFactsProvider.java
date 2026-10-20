package com.example.hangat.course.weather;

import com.example.hangat.course.model.CourseCandidateDto;
import com.example.hangat.course.model.CourseRequestDto;

import java.util.List;

/**
 * Optional boundary for supplying already verified weather facts to AI input preparation.
 * The DB implementation uses official region grids and the team's latest-per-date query.
 */
public interface CourseWeatherFactsProvider {

    CourseWeatherFacts load(
            CourseRequestDto request,
            List<CourseCandidateDto> candidates
    );
}
