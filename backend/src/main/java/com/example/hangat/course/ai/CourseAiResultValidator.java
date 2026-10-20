package com.example.hangat.course.ai;

import com.example.hangat.course.ai.CourseAiInputDto.CandidateFactDto;
import com.example.hangat.course.ai.CourseAiInputDto.RequiredCandidateConstraintDto;
import com.example.hangat.course.ai.CourseAiResultDto.DayDto;
import com.example.hangat.course.ai.CourseAiResultDto.ItemDto;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.example.hangat.course.CourseSchedulePolicy;

@Component
public class CourseAiResultValidator {

    static final int MAX_RECOMMENDATION_REASON_LENGTH = 300;

    public void validate(CourseAiInputDto input, CourseAiResultDto result) {
        validateInput(input);
        if (result == null) {
            fail(CourseAiValidationCode.AI_RESULT_MISSING, "AI 코스 결과가 없습니다.");
        }
        if (isBlank(result.contractVersion())) {
            fail(CourseAiValidationCode.AI_RESULT_CONTRACT_VERSION_MISSING,
                    "AI 코스 결과 계약 버전이 없습니다.");
        }
        if (!input.contractVersion().equals(result.contractVersion())) {
            fail(CourseAiValidationCode.AI_RESULT_CONTRACT_VERSION_MISMATCH,
                    "AI 코스 결과 계약 버전이 입력과 다릅니다.");
        }
        if (result.days() == null) {
            fail(CourseAiValidationCode.AI_RESULT_DAYS_MISSING,
                    "AI 코스 결과의 DAY 목록이 없습니다.");
        }
        if (result.days().isEmpty()) {
            fail(CourseAiValidationCode.AI_RESULT_DAYS_EMPTY,
                    "AI 코스 결과에 방문 일정이 없습니다.");
        }

        Map<String, CandidateFactDto> candidatesById = candidateMap(input.candidates());
        Map<String, ScheduledItem> scheduledByCandidate = new HashMap<>();
        Set<LocalDate> scheduledDates = new HashSet<>();
        LocalDate previousDate = null;

        for (DayDto day : result.days()) {
            validateDay(input, day, scheduledDates, previousDate);
            previousDate = day.date();
            LocalTime previousTime = null;
            String previousCandidateId = null;
            for (ItemDto item : day.items()) {
                validateItem(item, candidatesById, scheduledByCandidate,
                        day.date(), previousTime);
                validateSchedule(input, candidatesById, previousCandidateId, previousTime, item);
                previousTime = item.startTime();
                previousCandidateId = item.candidateId();
            }
        }

        validateRequired(input, candidatesById, scheduledByCandidate);
        validateCongestionPolicy(input, candidatesById, scheduledByCandidate);
        validateSelectedStyleCoverage(input, candidatesById, scheduledByCandidate);
        if (input.trip().startDate().datesUntil(input.trip().endDate().plusDays(1))
                .anyMatch(date -> !scheduledDates.contains(date))) {
            fail(CourseAiValidationCode.AI_RESULT_TRIP_DATE_MISSING,
                    "AI 코스 결과에 요청한 여행 날짜가 누락되었습니다.");
        }
    }

    /** 카페 선택은 카페만 방문한다는 뜻은 아니지만, 확인된 카페를 최소 한 곳 포함한다. */
    private void validateSelectedStyleCoverage(CourseAiInputDto input,
            Map<String, CandidateFactDto> candidates,
            Map<String, ScheduledItem> scheduled) {
        if (!input.preferences().selectedStyleCodes().contains("CAFE")) return;
        boolean cafeAvailable = candidates.values().stream().anyMatch(this::isCafe);
        boolean cafeScheduled = scheduled.keySet().stream()
                .map(candidates::get).filter(java.util.Objects::nonNull).anyMatch(this::isCafe);
        if (!cafeAvailable) {
            fail(CourseAiValidationCode.AI_RESULT_STYLE_CANDIDATE_MISSING,
                    "카페 스타일을 유지할 확인된 카페 후보가 부족합니다.");
        }
        if (!cafeScheduled) {
            fail(CourseAiValidationCode.AI_RESULT_SELECTED_STYLE_MISSING,
                    "카페 스타일을 선택했고 확인된 카페 후보가 있으므로 일정에 카페가 필요합니다.");
        }
    }

    private boolean isCafe(CandidateFactDto candidate) {
        return "CAFE".equals(candidate.internalCategoryCode())
                || candidate.styleHintCodes().contains("CAFE");
    }

    private void validateCongestionPolicy(CourseAiInputDto input,
            Map<String, CandidateFactDto> candidates,
            Map<String, ScheduledItem> scheduled) {
        Set<String> required = input.hardConstraints().requiredCandidates().stream()
                .map(RequiredCandidateConstraintDto::candidateId).collect(java.util.stream.Collectors.toSet());
        for (Map.Entry<String, ScheduledItem> entry : scheduled.entrySet()) {
            if (required.contains(entry.getKey())) continue; // WANT/fixed wins by the existing contract.
            CandidateFactDto chosen = candidates.get(entry.getKey());
            var chosenLevel = levelOn(chosen, entry.getValue().date());
            if (chosenLevel != com.example.hangat.map.model.enums.CongestionLevel.CROWDED) continue;
            boolean quieterAvailable = candidates.values().stream()
                    .filter(candidate -> !scheduled.containsKey(candidate.candidateId()))
                    .map(candidate -> levelOn(candidate, entry.getValue().date()))
                    .anyMatch(level -> level == com.example.hangat.map.model.enums.CongestionLevel.QUIET
                            || level == com.example.hangat.map.model.enums.CongestionLevel.NORMAL);
            if (quieterAvailable) {
                fail(CourseAiValidationCode.AI_RESULT_CONGESTION_POLICY_VIOLATION,
                        "혼잡한 장소 대신 같은 날짜에 확인된 더 한산한 후보를 사용해야 합니다.");
            }
        }
    }

    private com.example.hangat.map.model.enums.CongestionLevel levelOn(CandidateFactDto candidate, LocalDate date) {
        if (candidate == null || candidate.congestionFacts() == null) return null;
        return candidate.congestionFacts().stream().filter(fact -> date.equals(fact.date()))
                .map(CourseAiInputDto.CongestionFactDto::level).filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
    }

    private void validateSchedule(CourseAiInputDto input, Map<String, CandidateFactDto> candidates,
            String previousCandidateId, LocalTime previousTime, ItemDto item) {
        CandidateFactDto current = candidates.get(item.candidateId());
        int currentDwell = CourseSchedulePolicy.dwellMinutes(
                input.preferences().selectedStyleCodes(),
                current.styleHintCodes(), current.internalCategoryCode());
        if (item.startTime().toSecondOfDay() / 60 + currentDwell
                > CourseSchedulePolicy.DAY_END.toSecondOfDay() / 60) {
            fail(CourseAiValidationCode.AI_RESULT_DAY_DURATION_EXCEEDED,
                    "AI 코스 일정이 하루 운영 범위를 초과했습니다.");
        }
        if (previousCandidateId == null || previousTime == null) return;
        CandidateFactDto previous = candidates.get(previousCandidateId);
        int dwell = CourseSchedulePolicy.dwellMinutes(input.preferences().selectedStyleCodes(),
                previous.styleHintCodes(), previous.internalCategoryCode());
        int travel = input.travelFacts().stream()
                .filter(fact -> previousCandidateId.equals(fact.fromRef()) && item.candidateId().equals(fact.toRef()))
                .map(CourseAiInputDto.TravelFactDto::travelMinutes).filter(java.util.Objects::nonNull)
                .findFirst().orElse(0);
        if (item.startTime().toSecondOfDay()/60L < previousTime.toSecondOfDay()/60L + dwell + travel) {
            fail(CourseAiValidationCode.AI_RESULT_TRAVEL_TIME_OVERLAP,
                    "AI 코스 일정이 체류 및 추정 이동시간과 겹칩니다.");
        }
    }

    private void validateInput(CourseAiInputDto input) {
        if (input == null || input.trip() == null
                || input.trip().startDate() == null || input.trip().endDate() == null
                || isBlank(input.contractVersion()) || input.hardConstraints() == null
                || input.hardConstraints().requiredCandidates() == null
                || input.candidates() == null) {
            fail(CourseAiValidationCode.AI_RESULT_INPUT_INVALID,
                    "AI 코스 결과를 검증할 입력이 유효하지 않습니다.");
        }
    }

    private Map<String, CandidateFactDto> candidateMap(List<CandidateFactDto> candidates) {
        Map<String, CandidateFactDto> result = new HashMap<>();
        for (CandidateFactDto candidate : candidates) {
            if (candidate == null || isBlank(candidate.candidateId())) {
                fail(CourseAiValidationCode.AI_RESULT_INPUT_CANDIDATE_ID_MISSING,
                        "AI 코스 후보의 candidateId가 유효하지 않습니다.");
            }
            if (result.put(candidate.candidateId(), candidate) != null) {
                fail(CourseAiValidationCode.AI_RESULT_INPUT_DUPLICATE_CANDIDATE,
                        "AI 코스 입력에 중복 candidateId가 있습니다.");
            }
        }
        return result;
    }

    private void validateDay(
            CourseAiInputDto input,
            DayDto day,
            Set<LocalDate> scheduledDates,
            LocalDate previousDate
    ) {
        if (day == null) {
            fail(CourseAiValidationCode.AI_RESULT_DAY_MISSING,
                    "AI 코스 결과의 DAY가 없습니다.");
        }
        if (day.date() == null) {
            fail(CourseAiValidationCode.AI_RESULT_DAY_DATE_MISSING,
                    "AI 코스 결과의 방문일이 없습니다.");
        }
        LocalDate startDate = input.trip().startDate();
        LocalDate endDate = input.trip().endDate();
        if (day.date().isBefore(startDate) || day.date().isAfter(endDate)) {
            fail(CourseAiValidationCode.AI_RESULT_DAY_OUT_OF_RANGE,
                    "AI 코스 결과에 여행기간 밖 날짜가 있습니다.");
        }
        if (!scheduledDates.add(day.date())) {
            fail(CourseAiValidationCode.AI_RESULT_DUPLICATE_DAY,
                    "AI 코스 결과에 동일 날짜가 중복되었습니다.");
        }
        if (previousDate != null && day.date().isBefore(previousDate)) {
            fail(CourseAiValidationCode.AI_RESULT_DAY_ORDER_INVALID,
                    "AI 코스 결과의 DAY가 날짜 오름차순이 아닙니다.");
        }
        if (day.items() == null) {
            fail(CourseAiValidationCode.AI_RESULT_DAY_ITEMS_MISSING,
                    "AI 코스 결과의 DAY 항목 목록이 없습니다.");
        }
        if (day.items().isEmpty()) {
            fail(CourseAiValidationCode.AI_RESULT_DAY_ITEMS_EMPTY,
                    "AI 코스 결과에 방문 장소가 없는 DAY가 있습니다.");
        }
    }

    private void validateItem(
            ItemDto item,
            Map<String, CandidateFactDto> candidatesById,
            Map<String, ScheduledItem> scheduledByCandidate,
            LocalDate date,
            LocalTime previousTime
    ) {
        if (item == null || isBlank(item.candidateId())) {
            fail(CourseAiValidationCode.AI_RESULT_CANDIDATE_ID_MISSING,
                    "AI 코스 결과의 candidateId가 유효하지 않습니다.");
        }
        if (!candidatesById.containsKey(item.candidateId())) {
            fail(CourseAiValidationCode.AI_RESULT_UNKNOWN_CANDIDATE,
                    "AI 코스 결과에 입력 후보가 아닌 candidateId가 있습니다.");
        }
        if (item.startTime() == null) {
            fail(CourseAiValidationCode.AI_RESULT_START_TIME_MISSING,
                    "AI 코스 결과의 방문 시작 시간이 없습니다.");
        }
        if (previousTime != null && item.startTime().equals(previousTime)) {
            fail(CourseAiValidationCode.AI_RESULT_DUPLICATE_START_TIME,
                    "AI 코스 결과의 같은 DAY에 동일한 방문 시작 시간이 있습니다.");
        }
        if (previousTime != null && item.startTime().isBefore(previousTime)) {
            fail(CourseAiValidationCode.AI_RESULT_TIME_ORDER_INVALID,
                    "AI 코스 결과의 방문 시작 시간이 오름차순이 아닙니다.");
        }
        if (isBlank(item.recommendationReason())) {
            fail(CourseAiValidationCode.AI_RESULT_REASON_MISSING,
                    "AI 코스 결과의 추천 이유가 없습니다.");
        }
        if (item.recommendationReason().codePointCount(
                0, item.recommendationReason().length())
                > MAX_RECOMMENDATION_REASON_LENGTH) {
            fail(CourseAiValidationCode.AI_RESULT_REASON_TOO_LONG,
                    "AI 코스 결과의 추천 이유가 300자를 초과했습니다.");
        }
        if (scheduledByCandidate.put(item.candidateId(), new ScheduledItem(date, item)) != null) {
            fail(CourseAiValidationCode.AI_RESULT_DUPLICATE_CANDIDATE,
                    "AI 코스 결과에 같은 candidateId가 중복 배치되었습니다.");
        }
    }

    private void validateRequired(
            CourseAiInputDto input,
            Map<String, CandidateFactDto> candidatesById,
            Map<String, ScheduledItem> scheduledByCandidate
    ) {
        Set<String> requiredCandidateIds = new HashSet<>();
        for (RequiredCandidateConstraintDto constraint
                : input.hardConstraints().requiredCandidates()) {
            if (constraint == null || isBlank(constraint.candidateId())
                    || (constraint.fixedTime() != null && constraint.fixedDate() == null)
                    || !requiredCandidateIds.add(constraint.candidateId())) {
                fail(CourseAiValidationCode.AI_RESULT_INPUT_REQUIRED_CANDIDATE_INVALID,
                        "AI 코스 입력의 필수 후보 제약이 유효하지 않습니다.");
            }
            CandidateFactDto candidate = candidatesById.get(constraint.candidateId());
            if (candidate == null) {
                fail(CourseAiValidationCode.AI_RESULT_INPUT_REQUIRED_CANDIDATE_INVALID,
                        "WANT 장소와 일치하는 AI 코스 후보가 없습니다.");
            }
            ScheduledItem scheduled = scheduledByCandidate.get(candidate.candidateId());
            if (scheduled == null) {
                fail(CourseAiValidationCode.AI_RESULT_REQUIRED_CANDIDATE_MISSING,
                        "AI 코스 결과에서 WANT 장소가 누락되었습니다.");
            }
            if (constraint.fixedDate() != null && !constraint.fixedDate().equals(scheduled.date())) {
                fail(CourseAiValidationCode.AI_RESULT_FIXED_DATE_CHANGED,
                        "AI 코스 결과가 WANT 장소의 fixedDate를 변경했습니다.");
            }
            if (constraint.fixedTime() != null
                    && !constraint.fixedTime().equals(scheduled.item().startTime())) {
                fail(CourseAiValidationCode.AI_RESULT_FIXED_TIME_CHANGED,
                        "AI 코스 결과가 WANT 장소의 fixedTime을 변경했습니다.");
            }
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void fail(CourseAiValidationCode code, String message) {
        throw new CourseAiValidationException(code, message);
    }

    private record ScheduledItem(LocalDate date, ItemDto item) {
    }
}
