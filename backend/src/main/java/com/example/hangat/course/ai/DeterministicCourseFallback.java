package com.example.hangat.course.ai;

import com.example.hangat.course.ai.CourseAiInputDto.CandidateFactDto;
import com.example.hangat.course.ai.CourseAiInputDto.RequiredCandidateConstraintDto;
import com.example.hangat.course.ai.CourseAiInputDto.WeatherAiFactDto;
import com.example.hangat.course.CourseSchedulePolicy;
import com.example.hangat.course.service.RainyDayRule;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Bounded, provider-free fallback. It never relaxes hard constraints or invents places. */
@Component
public class DeterministicCourseFallback {
    private static final List<LocalTime> DEFAULT_TIMES = List.of(
            LocalTime.of(10, 0), LocalTime.of(14, 0), LocalTime.of(18, 0));

    public CourseAiResultDto generate(CourseAiInputDto input) {
        if (input == null || input.trip() == null || input.trip().startDate() == null
                || input.trip().endDate() == null || input.candidates().isEmpty()) throw unavailable();
        List<LocalDate> dates = input.trip().startDate().datesUntil(input.trip().endDate().plusDays(1)).toList();
        if (input.candidates().size() < dates.size()) throw unavailable();
        if (input.preferences().selectedStyleCodes().contains("CAFE")
                && input.candidates().stream().noneMatch(this::isCafe))
            throw new CourseAiValidationException(CourseAiValidationCode.AI_RESULT_STYLE_CANDIDATE_MISSING,
                    "카페 스타일을 유지할 확인된 카페 후보가 부족합니다.");
        boolean regenerate = input.generationMetadata() != null
                && input.generationMetadata().generationReason() == com.example.hangat.course.model.GenerationReason.USER_REGENERATE;
        if (regenerate && input.candidates().size() < 2) throw unavailable();

        Map<String, CandidateFactDto> byId = new LinkedHashMap<>();
        input.candidates().forEach(candidate -> byId.put(candidate.candidateId(), candidate));
        Map<LocalDate, List<CourseAiResultDto.ItemDto>> scheduled = new LinkedHashMap<>();
        dates.forEach(date -> scheduled.put(date, new ArrayList<>()));
        Set<String> used = new HashSet<>();

        for (RequiredCandidateConstraintDto required : input.hardConstraints().requiredCandidates()) {
            CandidateFactDto candidate = byId.get(required.candidateId());
            if (candidate == null || !used.add(required.candidateId())) throw unavailable();
            LocalDate date = required.fixedDate() == null ? leastLoaded(scheduled) : required.fixedDate();
            if (!scheduled.containsKey(date)) throw unavailable();
            LocalTime time = required.fixedTime() == null ? fittingTime(input, byId, candidate, scheduled.get(date)) : required.fixedTime();
            if (time == null || !fits(input,byId,candidate,time,scheduled.get(date))) throw unavailable();
            scheduled.get(date).add(item(candidate, time, input.preferences().selectedStyleCodes()));
        }

        Comparator<CandidateFactDto> stableOrder = Comparator
                .comparingInt((CandidateFactDto candidate) -> styleMatches(candidate, input)).reversed()
                .thenComparingInt(this::congestionRank)
                .thenComparing(CandidateFactDto::candidateId,
                        regenerate ? Comparator.reverseOrder() : Comparator.naturalOrder());
        List<CandidateFactDto> ordinary = new ArrayList<>(input.candidates().stream()
                .filter(candidate -> !used.contains(candidate.candidateId()))
                .sorted(stableOrder)
                .toList());
        // 후보마다 "비 예보인 여행 날짜"를 미리 센다 - 후보 권역의 weatherFactSet 기준이라 권역별로 다를 수 있다
        Map<String, Set<LocalDate>> rainyDates = new HashMap<>();
        for (CandidateFactDto candidate : ordinary) {
            Set<LocalDate> rainy = new HashSet<>();
            for (LocalDate date : dates) if (rainyOn(input, candidate, date)) rainy.add(date);
            rainyDates.put(candidate.candidateId(), rainy);
        }
        // Populate empty dates first, then balance additional visits without consuming later days' candidates.
        // 우천 규칙(배치 코스 SampleCourseGenerator와 같은 임계값 RainyDayRule.PROB_FROM): 후보를 고르는 순서는 그대로 두고
        //   날짜만 맞춘다 - 실내 후보는 비 예보일로, 실외 후보는 비 예보일이 아닌 날로. 남은 후보가 빈 날짜 수를 겨우 채울
        //   만큼이면 빈 날짜부터 채운다(우천 선호 때문에 하루가 비지 않게). 비 예보일이 없는 여행은 기존 결과와 같다.
        List<CandidateFactDto> deferred = new ArrayList<>();
        int placedAtPassStart = used.size();
        while (!ordinary.isEmpty() || !deferred.isEmpty()) {
            if (ordinary.isEmpty()) {
                // Retry only after progress: at most one successful placement per candidate.
                if (used.size() == placedAtPassStart) break;
                ordinary.addAll(deferred);
                deferred.clear();
                placedAtPassStart = used.size();
            }
            Set<String> covered = new HashSet<>();
            used.stream().map(byId::get).forEach(c -> {
                covered.addAll(c.styleHintCodes());
                if(isCafe(c))covered.add("CAFE");
            });
            boolean needsCafe=input.preferences().selectedStyleCodes().contains("CAFE") && !covered.contains("CAFE");
            ordinary.sort(Comparator.comparing((CandidateFactDto c)->needsCafe && !isCafe(c))
                    .thenComparing(Comparator.comparingLong((CandidateFactDto c)->c.styleHintCodes().stream()
                            .filter(input.preferences().selectedStyleCodes()::contains).filter(s->!covered.contains(s)).count()).reversed())
                    .thenComparing(stableOrder));
            CandidateFactDto candidate = ordinary.remove(0);
            Set<LocalDate> rainy = rainyDates.get(candidate.candidateId());
            boolean fillEmptyFirst = ordinary.size() + 1 <= scheduled.values().stream().filter(List::isEmpty).count();
            LocalDate date = scheduled.entrySet().stream()
                    .filter(entry -> entry.getValue().size() < 3
                            && respectsCongestionPolicy(input,candidate,entry.getKey(),used)
                            && fittingTime(input,byId,candidate,entry.getValue()) != null)
                    .min(Comparator.comparingInt((Map.Entry<LocalDate, List<CourseAiResultDto.ItemDto>> entry)
                            -> fillEmptyFirst && entry.getValue().isEmpty() ? 0 : 1)
                            .thenComparingInt(entry -> rainPreference(candidate, entry.getKey(), rainy))
                            .thenComparingInt(entry -> entry.getValue().size())
                            .thenComparing(Map.Entry::getKey))
                    .map(Map.Entry::getKey).orElse(null);
            if (date == null) {
                deferred.add(candidate);
                continue;
            }
            LocalTime time = fittingTime(input,byId,candidate,scheduled.get(date));
            used.add(candidate.candidateId());
            scheduled.get(date).add(item(candidate, time, input.preferences().selectedStyleCodes(),
                    rainPreference(candidate, date, rainy) == 0));
        }
        if (scheduled.values().stream().anyMatch(List::isEmpty)) throw unavailable();
        return new CourseAiResultDto(input.contractVersion(), scheduled.entrySet().stream()
                .map(entry -> new CourseAiResultDto.DayDto(entry.getKey(), entry.getValue().stream()
                        .sorted(Comparator.comparing(CourseAiResultDto.ItemDto::startTime)).toList()))
                .toList());
    }

    private boolean respectsCongestionPolicy(CourseAiInputDto input, CandidateFactDto candidate,
                                             LocalDate date, Set<String> used) {
        if (levelOn(candidate,date) != com.example.hangat.map.model.enums.CongestionLevel.CROWDED) return true;
        return input.candidates().stream().filter(c -> !used.contains(c.candidateId()))
                .map(c -> levelOn(c,date)).noneMatch(level ->
                        level == com.example.hangat.map.model.enums.CongestionLevel.QUIET
                        || level == com.example.hangat.map.model.enums.CongestionLevel.NORMAL);
    }

    private com.example.hangat.map.model.enums.CongestionLevel levelOn(CandidateFactDto candidate, LocalDate date) {
        return candidate.congestionFacts().stream().filter(f -> date.equals(f.date()))
                .map(CourseAiInputDto.CongestionFactDto::level).filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    private LocalTime fittingTime(CourseAiInputDto input, Map<String,CandidateFactDto> byId,
                                  CandidateFactDto candidate, List<CourseAiResultDto.ItemDto> items) {
        return DEFAULT_TIMES.stream().filter(t->fits(input,byId,candidate,t,items)).findFirst().orElse(null);
    }

    private boolean fits(CourseAiInputDto input, Map<String,CandidateFactDto> byId, CandidateFactDto candidate,
                         LocalTime time, List<CourseAiResultDto.ItemDto> items) {
        if(items.stream().anyMatch(i->time.equals(i.startTime())))return false;
        var timeline=new ArrayList<>(items);
        timeline.add(item(candidate,time,input.preferences().selectedStyleCodes()));
        timeline.sort(Comparator.comparing(CourseAiResultDto.ItemDto::startTime));
        for(int i=0;i<timeline.size();i++) {
            var current=timeline.get(i);
            var fact=byId.get(current.candidateId());
            long end=current.startTime().toSecondOfDay()/60L + CourseSchedulePolicy.dwellMinutes(
                    input.preferences().selectedStyleCodes(),fact.styleHintCodes(),fact.internalCategoryCode());
            if(end>CourseSchedulePolicy.DAY_END.toSecondOfDay()/60)return false;
            if(i+1<timeline.size()) {
                var next=timeline.get(i+1);
                int travel=input.travelFacts().stream().filter(t->current.candidateId().equals(t.fromRef()) && next.candidateId().equals(t.toRef()))
                        .map(CourseAiInputDto.TravelFactDto::travelMinutes).filter(java.util.Objects::nonNull).findFirst().orElse(0);
                if(end+travel>next.startTime().toSecondOfDay()/60)return false;
            }
        }
        return true;
    }

    private int congestionRank(CandidateFactDto candidate) {
        if (candidate.congestionFacts() == null || candidate.congestionFacts().isEmpty()) return 2;
        return candidate.congestionFacts().stream().map(CourseAiInputDto.CongestionFactDto::level)
                .filter(java.util.Objects::nonNull).mapToInt(level -> switch (level) {
                    case QUIET -> 0; case NORMAL -> 1; case CROWDED -> 3;
                }).min().orElse(2);
    }

    private int styleMatches(CandidateFactDto candidate, CourseAiInputDto input) {
        return (int) candidate.styleHintCodes().stream()
                .filter(input.preferences().selectedStyleCodes()::contains).count();
    }

    private boolean isCafe(CandidateFactDto candidate) {
        return "CAFE".equals(candidate.internalCategoryCode())
                || candidate.styleHintCodes().contains("CAFE");
    }

    private CourseAiResultDto.ItemDto item(CandidateFactDto candidate, LocalTime time, List<String> styles) {
        return item(candidate, time, styles, false);
    }

    /** rainyIndoor - 비 예보일에 실내라서 그날로 잡은 항목. 사유는 배치 코스와 같은 문장. */
    private CourseAiResultDto.ItemDto item(CandidateFactDto candidate, LocalTime time, List<String> styles,
                                           boolean rainyIndoor) {
        String matched = candidate.styleHintCodes().stream().filter(styles::contains).findFirst().orElse(null);
        String reason = rainyIndoor
                ? RainyDayRule.INDOOR_REASON
                : matched == null
                ? "확인된 후보와 날짜 조건을 기준으로 안정적으로 구성했어요."
                : "선택한 " + matched + " 스타일과 확인된 후보 정보를 기준으로 구성했어요.";
        return new CourseAiResultDto.ItemDto(candidate.candidateId(), time, reason);
    }

    /**
     * 후보에게 이 날짜가 얼마나 맞는가 - 비 예보일이면 실내 후보는 그날로(0), 실외 후보는 다른 날로(2). 그 밖엔 중립(1).
     * 후보를 고르는 순서에는 손대지 않는다 - 취향·혼잡 순서는 그대로고 날짜만 비 예보에 맞춘다.
     */
    private int rainPreference(CandidateFactDto candidate, LocalDate date, Set<LocalDate> rainyDates) {
        if (!rainyDates.contains(date)) return 1;
        return candidate.indoor() ? 0 : 2;
    }

    /**
     * 그 날짜가 이 후보에게 비 예보일인가 - 후보 권역의 weatherFactSet에서 그 날짜 강수확률을 본다.
     * 일 단위 사실(forecastTime 없음)이 있으면 그것을, 없으면 시간대 사실의 최대값을 쓴다.
     * 날씨 세트가 없거나 강수확률이 없으면 비로 단정하지 않는다 - 모르는 것을 경고로 바꾸지 않는다.
     */
    private boolean rainyOn(CourseAiInputDto input, CandidateFactDto candidate, LocalDate date) {
        if (candidate.weatherFactSetId() == null || input.weatherFactSets() == null) return false;
        List<WeatherAiFactDto> facts = input.weatherFactSets().stream()
                .filter(set -> candidate.weatherFactSetId().equals(set.weatherFactSetId()))
                .flatMap(set -> set.facts().stream())
                .filter(fact -> date.equals(fact.forecastDate()))
                .toList();
        Integer probability = facts.stream()
                .filter(fact -> fact.forecastTime() == null)
                .map(WeatherAiFactDto::precipitationProbability)
                .filter(Objects::nonNull)
                .findFirst()
                .orElseGet(() -> facts.stream()
                        .map(WeatherAiFactDto::precipitationProbability)
                        .filter(Objects::nonNull)
                        .max(Integer::compare)
                        .orElse(null));
        return RainyDayRule.isRainy(probability);
    }

    private LocalDate leastLoaded(Map<LocalDate, List<CourseAiResultDto.ItemDto>> scheduled) {
        return scheduled.entrySet().stream().min(Comparator
                .comparingInt((Map.Entry<LocalDate, List<CourseAiResultDto.ItemDto>> entry) -> entry.getValue().size())
                .thenComparing(Map.Entry::getKey)).orElseThrow().getKey();
    }

    private CourseAiException unavailable() {
        return new CourseAiException(CourseAiFailureType.TEMPORARILY_UNAVAILABLE,
                "필수 조건을 유지한 대체 코스를 구성할 후보가 부족합니다.");
    }
}
