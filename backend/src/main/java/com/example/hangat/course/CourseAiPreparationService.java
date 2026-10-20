package com.example.hangat.course;

import com.example.hangat.course.ai.CourseAiInputDto;
import com.example.hangat.course.ai.CourseAiInputDto.GenerationMetadataDto;
import com.example.hangat.course.facts.CourseGenerationFacts;
import com.example.hangat.course.model.CourseCandidateDto;
import com.example.hangat.course.model.CourseRequestDto;
import com.example.hangat.course.model.GenerationReason;
import com.example.hangat.course.travel.CourseTravelLegDto;
import com.example.hangat.course.travel.CourseTravelService;
import com.example.hangat.course.weather.CourseWeatherFacts;
import com.example.hangat.course.weather.CourseWeatherFactsProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class CourseAiPreparationService {

    private final CourseAiInputAssembler assembler;
    private final CourseTravelService travelService;
    private final Optional<CourseWeatherFactsProvider> weatherFactsProvider;

    public CourseAiPreparationService(
            CourseAiInputAssembler assembler,
            CourseTravelService travelService,
            Optional<CourseWeatherFactsProvider> weatherFactsProvider
    ) {
        this.assembler = assembler;
        this.travelService = travelService;
        this.weatherFactsProvider = weatherFactsProvider;
    }

    public CourseAiInputDto prepare(
            CourseRequestDto request,
            List<CourseCandidateDto> candidates
    ) {
        return prepareGeneration(request, candidates).input();
    }

    PreparedGeneration prepareGeneration(
            CourseRequestDto request,
            List<CourseCandidateDto> candidates
    ) {
        List<CourseCandidateDto> safeCandidates = candidates == null
                ? List.of()
                : List.copyOf(candidates);
        CourseWeatherFacts weatherFacts = weatherFactsProvider
                .map(provider -> provider.load(request, safeCandidates))
                .orElseGet(CourseWeatherFacts::empty);

        if (weatherFacts == null) {
            weatherFacts = CourseWeatherFacts.empty();
        }

        CourseGenerationFactsAssembler.Assembly generationFacts =
                new CourseGenerationFactsAssembler().assemble(
                        request,
                        safeCandidates,
                        weatherFacts,
                        buildBoundedTravelFacts(request, safeCandidates));
        CourseGenerationMetadata metadata = new CourseGenerationMetadata(
                request.isRegenerate() ? GenerationReason.USER_REGENERATE : GenerationReason.INITIAL,
                null, null);

        CourseAiInputDto input = assembler.assemble(
                request,
                generationFacts,
                new GenerationMetadataDto(
                        metadata.generationReason(),
                        metadata.algorithmVersion(),
                        metadata.requestReference()));
        return new PreparedGeneration(generationFacts.facts(), input, metadata);
    }

    private List<CourseTravelLegDto> buildBoundedTravelFacts(
            CourseRequestDto request,
            List<CourseCandidateDto> candidates
    ) {
        List<CourseTravelLegDto> travelFacts = new ArrayList<>();

        // The shortlist is capped at 30. Computing local estimates for every directed pair
        // lets the generator validate any chosen order without making an external route call.
        for (int fromIndex = 0; fromIndex < candidates.size(); fromIndex++) {
            for (int toIndex = 0; toIndex < candidates.size(); toIndex++) {
                if (fromIndex == toIndex) continue;
                CourseCandidateDto from = candidates.get(fromIndex);
                CourseCandidateDto to = candidates.get(toIndex);

                if (from == null || from.getPlace() == null || to == null || to.getPlace() == null) continue;

                travelService.calculateStraightLineLeg(
                        from.getPlace(), to.getPlace(), request.getTransport()
                ).ifPresent(travelFacts::add);
            }
        }

        return List.copyOf(travelFacts);
    }

    record PreparedGeneration(
            CourseGenerationFacts facts,
            CourseAiInputDto input,
            CourseGenerationMetadata metadata
    ) {
    }
}
