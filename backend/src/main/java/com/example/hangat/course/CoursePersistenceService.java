package com.example.hangat.course;

import com.example.hangat.course.ai.CourseAiResultDto;
import com.example.hangat.course.facts.CandidateIdentity;
import com.example.hangat.course.facts.CourseCandidate;
import com.example.hangat.course.facts.CourseGenerationFacts;
import com.example.hangat.course.facts.InternalPlaceCategory;
import com.example.hangat.course.facts.PlaceFact;
import com.example.hangat.course.model.CourseRequestDto;
import com.example.hangat.course.model.PreferenceType;
import com.example.hangat.course.model.entity.Course;
import com.example.hangat.course.model.entity.CourseItem;
import com.example.hangat.course.model.enums.CourseItemSource;
import com.example.hangat.course.model.enums.CourseStatus;
import com.example.hangat.course.model.enums.CourseType;
import com.example.hangat.course.repository.CourseItemRepository;
import com.example.hangat.course.repository.CourseRepository;
import com.example.hangat.map.model.entity.Place;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Service
public class CoursePersistenceService {

    private static final int MAX_RECOMMENDATION_REASON_LENGTH = 300;

    private final CourseRepository courseRepository;
    private final CourseItemRepository courseItemRepository;
    private final CoursePlaceResolver placeResolver;

    public CoursePersistenceService(
            CourseRepository courseRepository,
            CourseItemRepository courseItemRepository,
            CoursePlaceResolver placeResolver
    ) {
        this.courseRepository = courseRepository;
        this.courseItemRepository = courseItemRepository;
        this.placeResolver = placeResolver;
    }

    @Transactional
    public CoursePersistenceResult persist(
            CourseRequestDto request,
            CourseGenerationFacts facts,
            CourseAiResultDto result,
            CourseGenerationMetadata metadata
    ) {
        if (request == null || facts == null || result == null || metadata == null) {
            throw new IllegalArgumentException("저장할 코스 생성 결과가 필요합니다.");
        }
        if (result.days() == null || result.days().isEmpty()
                || result.days().stream().allMatch(day -> day == null
                        || day.items() == null || day.items().isEmpty())) {
            throw new IllegalArgumentException("방문 일정이 없는 코스는 저장할 수 없습니다.");
        }

        Map<String, CourseCandidate> candidatesById = indexCandidates(facts);
        validateSelectedCandidates(result, candidatesById);

        Course course = courseRepository.save(Course.builder()
                .courseType(CourseType.USER)
                .generationReason(com.example.hangat.course.model.enums.GenerationReason.valueOf(
                        metadata.generationReason().name()))
                .status(CourseStatus.GENERATING)
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .people(toPeople(request.getPeople()))
                .budgetTotal(request.getBudgetTotal())
                .transport(com.example.hangat.course.model.enums.Transport.valueOf(
                        request.getTransport().name()))
                .algorithmVersion(metadata.algorithmVersion())
                .build());

        Map<String, CourseItem> itemsByCandidateId = new LinkedHashMap<>();
        Map<String, String> categoryNamesByCandidateId = new LinkedHashMap<>();
        for (int dayIndex = 0; dayIndex < result.days().size(); dayIndex++) {
            CourseAiResultDto.DayDto day = result.days().get(dayIndex);
            int dayNo = dayIndex + 1;

            for (int itemIndex = 0; itemIndex < day.items().size(); itemIndex++) {
                CourseAiResultDto.ItemDto resultItem = day.items().get(itemIndex);
                CourseCandidate candidate = candidatesById.get(resultItem.candidateId());
                Place place = placeResolver.resolvePlace(candidate);
                CourseItem item = courseItemRepository.save(CourseItem.builder()
                        .course(course)
                        .place(place)
                        .dayNo(toShort(dayNo, "dayNo"))
                        .position(toShort(itemIndex + 1, "position"))
                        .visitDate(day.date())
                        .startTime(resultItem.startTime())
                        .itemSource(resolveItemSource(candidate))
                        .recommendationReason(resultItem.recommendationReason())
                        .build());
                itemsByCandidateId.put(resultItem.candidateId(), item);
                categoryNamesByCandidateId.put(
                        resultItem.candidateId(), place.getPrimaryCategory().getName());
            }
        }

        course.markReady();
        courseRepository.save(course);

        return new CoursePersistenceResult(
                course, itemsByCandidateId, categoryNamesByCandidateId);
    }

    private Map<String, CourseCandidate> indexCandidates(CourseGenerationFacts facts) {
        Map<String, CourseCandidate> result = new LinkedHashMap<>();
        for (CourseCandidate candidate : facts.candidates()) {
            if (candidate == null || candidate.identity() == null
                    || isBlank(candidate.identity().candidateId())) {
                throw new IllegalStateException("저장 후보의 candidateId가 유효하지 않습니다.");
            }
            String candidateId = candidate.identity().candidateId();
            if (result.putIfAbsent(candidateId, candidate) != null) {
                throw new IllegalStateException(
                        "저장 후보에 중복 candidateId가 있습니다: " + candidateId);
            }
        }
        return Map.copyOf(result);
    }

    private void validateSelectedCandidates(
            CourseAiResultDto result,
            Map<String, CourseCandidate> candidatesById
    ) {
        Set<String> selectedCandidateIds = new HashSet<>();
        for (CourseAiResultDto.DayDto day : result.days()) {
            if (day == null || day.date() == null || day.items() == null) {
                throw new IllegalStateException("저장할 AI 일정이 유효하지 않습니다.");
            }
            for (CourseAiResultDto.ItemDto item : day.items()) {
                if (item == null || isBlank(item.candidateId())) {
                    throw new IllegalStateException("저장할 candidateId가 유효하지 않습니다.");
                }
                if (!selectedCandidateIds.add(item.candidateId())) {
                    throw new IllegalStateException(
                            "저장 일정에 중복 candidateId가 있습니다: " + item.candidateId());
                }
                CourseCandidate candidate = candidatesById.get(item.candidateId());
                if (candidate == null) {
                    throw new IllegalStateException(
                            "저장할 수 없는 AI 후보 식별자입니다: " + item.candidateId());
                }
                validateCandidateForStorage(candidate);
                validateRecommendationReason(item.recommendationReason());
            }
        }
    }

    private void validateCandidateForStorage(CourseCandidate candidate) {
        CandidateIdentity identity = candidate.identity();
        PlaceFact place = candidate.place();
        if (place == null || isBlank(place.name())) {
            throw new IllegalStateException("장소명이 없는 후보는 저장할 수 없습니다.");
        }
        if (isBlank(candidate.regionCode())
                || CourseAiInputAssembler.UNKNOWN_REGION.equalsIgnoreCase(
                        candidate.regionCode())) {
            throw new IllegalStateException("권역을 확인할 수 없는 후보는 저장할 수 없습니다.");
        }
        InternalPlaceCategory category = candidate.internalPlaceCategory();
        if (category == null || isBlank(category.code())) {
            throw new IllegalStateException("내부 장소 카테고리가 없는 후보는 저장할 수 없습니다.");
        }
        if (identity.placeId() == null && !hasSourceIdentity(identity)) {
            throw new IllegalStateException(
                    "내부 또는 외부 장소 식별자가 없는 후보는 저장할 수 없습니다.");
        }
        if (identity.placeId() == null) {
            placeResolver.validateCandidateReferences(candidate);
        }
    }

    private void validateRecommendationReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalStateException("추천 이유가 없는 일정은 저장할 수 없습니다.");
        }
        if (reason.codePointCount(0, reason.length())
                > MAX_RECOMMENDATION_REASON_LENGTH) {
            throw new IllegalStateException("추천 이유가 300자를 초과했습니다.");
        }
    }

    private short toPeople(Integer people) {
        return toShort(people, "people");
    }

    private short toShort(Integer value, String field) {
        if (value == null || value < 0 || value > Short.MAX_VALUE) {
            throw new IllegalArgumentException(field + " 값이 SMALLINT 범위를 벗어났습니다.");
        }
        return value.shortValue();
    }

    private CourseItemSource resolveItemSource(CourseCandidate candidate) {
        boolean userFixed = candidate.userConstraint().preferenceType()
                == PreferenceType.WANT
                && candidate.userConstraint().fixedDate() != null;
        return userFixed ? CourseItemSource.USER_FIXED : CourseItemSource.AI_RECOMMENDED;
    }

    private boolean hasSourceIdentity(CandidateIdentity identity) {
        return !isBlank(identity.sourceCode()) && !isBlank(identity.sourcePlaceId());
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
