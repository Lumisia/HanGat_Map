package com.example.hangat.course;

import com.example.hangat.course.ai.CourseAiResultDto;
import com.example.hangat.course.facts.CongestionFact;
import com.example.hangat.course.facts.CourseCandidate;
import com.example.hangat.course.facts.CourseGenerationFacts;
import com.example.hangat.course.facts.StyleHint;
import com.example.hangat.course.facts.TravelFact;
import com.example.hangat.course.facts.WeatherFactSet;
import com.example.hangat.course.model.AccommodationDto;
import com.example.hangat.course.model.CourseResponseDto;
import com.example.hangat.course.model.CourseResponseDto.BudgetSummaryDto;
import com.example.hangat.course.model.CourseResponseDto.CourseItemCostDto;
import com.example.hangat.course.model.CourseResponseDto.CongestionFactDto;
import com.example.hangat.course.model.CourseResponseDto.DayDto;
import com.example.hangat.course.model.CourseResponseDto.ItemDto;
import com.example.hangat.course.model.CourseResponseDto.ItemSource;
import com.example.hangat.course.model.CourseResponseDto.WeatherFactDto;
import com.example.hangat.course.model.entity.Course;
import com.example.hangat.course.model.entity.CourseItem;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class CourseResponseAssembler {

    public CourseResponseDto assemble(
            CourseGenerationFacts facts,
            CourseAiResultDto result,
            CoursePersistenceResult persistence,
            AccommodationDto accommodation
    ) {
        return assemble(facts, result, persistence, accommodation,
                CourseBudgetCalculation.noData());
    }

    public CourseResponseDto assemble(
            CourseGenerationFacts facts,
            CourseAiResultDto result,
            CoursePersistenceResult persistence,
            AccommodationDto accommodation,
            CourseBudgetCalculation budget
    ) {
        if (facts == null) {
            throw new IllegalArgumentException("코스 생성 사실이 필요합니다.");
        }
        if (result == null || result.days() == null) {
            throw new IllegalArgumentException("AI 코스 생성 결과가 필요합니다.");
        }
        if (persistence == null || persistence.course() == null) {
            throw new IllegalArgumentException("코스 저장 결과가 필요합니다.");
        }
        if (budget == null || budget.summary() == null) {
            throw new IllegalArgumentException("코스 예산 계산 결과가 필요합니다.");
        }

        Map<String, CourseCandidate> candidatesById = indexCandidates(facts.candidates());
        Map<String, WeatherFactSet> weatherFactSetsById = indexWeatherFactSets(
                facts.weatherFactSets());
        Map<TravelPair, TravelFact> travelFactsByPair = indexTravelFacts(facts.travelFacts());

        List<DayDto> days = result.days().stream()
                .map(day -> toDay(
                        day,
                        candidatesById,
                        weatherFactSetsById,
                        travelFactsByPair,
                        persistence,
                        budget))
                .toList();

        Course course = persistence.course();
        BudgetSummaryDto budgetSummary = toBudgetSummary(budget);
        return new CourseResponseDto(
                course.getId(),
                result.contractVersion(),
                course.getCourseType(),
                course.getGenerationReason(),
                course.getStatus(),
                course.getStartDate(),
                course.getEndDate(),
                course.getPeople(),
                course.getTransport(),
                accommodation,
                budget.totalExpectedMin(),
                budget.totalExpectedMax(),
                budgetSummary,
                course.getAverageCongestionRate(),
                days);
    }

    private DayDto toDay(
            CourseAiResultDto.DayDto day,
            Map<String, CourseCandidate> candidatesById,
            Map<String, WeatherFactSet> weatherFactSetsById,
            Map<TravelPair, TravelFact> travelFactsByPair,
            CoursePersistenceResult persistence,
            CourseBudgetCalculation budget
    ) {
        if (day == null || day.items() == null || day.items().isEmpty()) {
            throw new IllegalArgumentException("응답으로 변환할 AI 일정이 유효하지 않습니다.");
        }

        List<ItemDto> items = java.util.stream.IntStream.range(0, day.items().size())
                .mapToObj(itemIndex -> toItem(
                        day.items().get(itemIndex),
                        itemIndex == 0 ? null : day.items().get(itemIndex - 1).candidateId(),
                        candidatesById,
                        weatherFactSetsById,
                        travelFactsByPair,
                        persistence,
                        budget))
                .toList();
        return new DayDto(items.get(0).dayNo(), items.get(0).visitDate(), items);
    }

    private ItemDto toItem(
            CourseAiResultDto.ItemDto item,
            String previousCandidateId,
            Map<String, CourseCandidate> candidatesById,
            Map<String, WeatherFactSet> weatherFactSetsById,
            Map<TravelPair, TravelFact> travelFactsByPair,
            CoursePersistenceResult persistence,
            CourseBudgetCalculation budget
    ) {
        CourseCandidate candidate = candidatesById.get(item.candidateId());
        if (candidate == null) {
            throw new IllegalArgumentException(
                    "응답으로 변환할 수 없는 AI 후보 식별자입니다: " + item.candidateId());
        }
        CourseItem persistedItem = persistence.itemsByCandidateId().get(item.candidateId());
        if (persistedItem == null) {
            throw new IllegalArgumentException(
                    "응답으로 변환할 저장 항목이 없습니다: " + item.candidateId());
        }

        List<CongestionFactDto> congestion = candidate.congestionFacts().stream()
                .map(this::toCongestionFact)
                .toList();
        CongestionFactDto displayedCongestion = congestion.stream()
                .filter(fact -> persistedItem.getVisitDate().equals(fact.date()))
                .findFirst()
                .orElse(null);
        List<WeatherFactDto> weather = resolveWeather(
                candidate, persistedItem, weatherFactSetsById);
        TravelFact inboundTravel = previousCandidateId == null
                ? null
                : travelFactsByPair.get(new TravelPair(
                        previousCandidateId, item.candidateId()));

        BigDecimal inboundDistance = null;
        Integer inboundTravelMinutes = null;
        if (previousCandidateId != null) {
            inboundDistance = persistedItem.getInboundDistanceM() == null
                    ? (inboundTravel == null ? null : inboundTravel.routeDistanceMeters())
                    : BigDecimal.valueOf(persistedItem.getInboundDistanceM());
            Short persistedTravelMinutes = persistedItem.getInboundTravelMinutes();
            inboundTravelMinutes = persistedTravelMinutes == null
                    ? (inboundTravel == null ? null : inboundTravel.travelMinutes())
                    : Integer.valueOf(persistedTravelMinutes);
        }

        return new ItemDto(
                persistedItem.getId(),
                persistedItem.getCourse().getId(),
                persistedItem.getPlace().getId(),
                candidate.identity().candidateId(),
                candidate.identity().sourceCode(),
                candidate.identity().sourcePlaceId(),
                candidate.place().name(),
                candidate.place().address(),
                candidate.place().roadAddress(),
                candidate.place().latitude() == null
                        ? null : candidate.place().latitude().doubleValue(),
                candidate.place().longitude() == null
                        ? null : candidate.place().longitude().doubleValue(),
                candidate.place().imageUrl(),
                categoryName(candidate, persistence),
                candidate.regionCode(),
                candidate.userConstraint().preferenceType(),
                candidate.styleHints().stream().map(StyleHint::styleCode).toList(),
                persistedItem.getDayNo(),
                persistedItem.getPosition(),
                persistedItem.getVisitDate(),
                persistedItem.getStartTime(),
                ItemSource.valueOf(persistedItem.getItemSource().name()),
                persistedItem.getRecommendationReason(),
                budget.itemCostsByItemId()
                        .getOrDefault(persistedItem.getId(), List.of()).stream()
                        .map(CourseResponseAssembler::toCostDto)
                        .toList(),
                inboundDistance,
                inboundTravelMinutes,
                displayedCongestion == null ? null : displayedCongestion.rate(),
                displayedCongestion == null ? null : displayedCongestion.level(),
                congestion,
                weather);
    }

    public static BudgetSummaryDto toBudgetSummary(CourseBudgetCalculation budget) {
        CourseBudgetCalculation.BudgetSummary summary = budget.summary();
        return new BudgetSummaryDto(
                summary.hasCostData(),
                summary.verifiedTotal(),
                summary.estimatedTotal(),
                summary.estimatedMin(),
                summary.estimatedMax(),
                summary.totalExpected(),
                budget.totalExpectedMin(),
                budget.totalExpectedMax(),
                summary.unknownCount());
    }

    public static CourseItemCostDto toCostDto(CourseBudgetCalculation.CostLine cost) {
        return new CourseItemCostDto(
                cost.id(),
                cost.courseId(),
                cost.courseItemId(),
                cost.category(),
                cost.accuracyType(),
                cost.amountMin() == null ? null : BigDecimal.valueOf(cost.amountMin()),
                cost.amountMax() == null ? null : BigDecimal.valueOf(cost.amountMax()),
                cost.currency(),
                cost.basisText());
    }

    private String categoryName(
            CourseCandidate candidate,
            CoursePersistenceResult persistence
    ) {
        String persistedName = persistence.categoryNamesByCandidateId()
                .get(candidate.identity().candidateId());
        return persistedName == null || persistedName.isBlank()
                ? candidate.internalPlaceCategory().name()
                : persistedName;
    }

    private CongestionFactDto toCongestionFact(CongestionFact fact) {
        return new CongestionFactDto(fact.date(), fact.rate(), fact.level());
    }

    private List<WeatherFactDto> resolveWeather(
            CourseCandidate candidate,
            CourseItem persistedItem,
            Map<String, WeatherFactSet> weatherFactSetsById
    ) {
        if (candidate.weatherFactSetId() == null) {
            return null;
        }
        WeatherFactSet factSet = weatherFactSetsById.get(candidate.weatherFactSetId());
        if (factSet == null) {
            throw new IllegalArgumentException(
                    "응답 후보가 존재하지 않는 날씨 fact set을 참조합니다: "
                            + candidate.weatherFactSetId());
        }
        return factSet.facts().stream()
                .filter(fact -> persistedItem.getVisitDate().equals(fact.forecastDate()))
                .map(WeatherFactDto::from)
                .toList();
    }

    private Map<String, CourseCandidate> indexCandidates(List<CourseCandidate> candidates) {
        return candidates.stream().collect(Collectors.toMap(
                candidate -> candidate.identity().candidateId(),
                Function.identity(),
                (first, second) -> {
                    throw new IllegalArgumentException(
                            "응답 후보에 중복 candidateId가 있습니다: "
                                    + first.identity().candidateId());
                },
                LinkedHashMap::new));
    }

    private Map<String, WeatherFactSet> indexWeatherFactSets(List<WeatherFactSet> factSets) {
        return factSets.stream().collect(Collectors.toMap(
                WeatherFactSet::weatherFactSetId,
                Function.identity(),
                (first, second) -> {
                    throw new IllegalArgumentException(
                            "응답 날씨에 중복 weatherFactSetId가 있습니다: "
                                    + first.weatherFactSetId());
                },
                LinkedHashMap::new));
    }

    private Map<TravelPair, TravelFact> indexTravelFacts(List<TravelFact> travelFacts) {
        return travelFacts.stream().collect(Collectors.toMap(
                fact -> new TravelPair(fact.fromCandidateId(), fact.toCandidateId()),
                Function.identity(),
                (first, second) -> {
                    throw new IllegalArgumentException(
                            "응답 이동정보에 중복 후보 pair가 있습니다: "
                                    + first.fromCandidateId() + "->" + first.toCandidateId());
                },
                LinkedHashMap::new));
    }

    private record TravelPair(String fromCandidateId, String toCandidateId) {
    }
}
