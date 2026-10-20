package com.example.hangat.course.ai;

import com.example.hangat.course.ai.CourseAiInputDto.CandidateFactDto;
import com.example.hangat.course.model.Transport;
import com.example.hangat.course.service.RainyDayRule;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 우천 규칙이 프롬프트와 후보 JSON에 실제로 실리는지 - 지금까지 시스템 프롬프트 내용을 단언하는 테스트가 없어
 * 규칙이 빠져도 아무도 몰랐다. 임계값은 RainyDayRule 하나에서 온다.
 */
class CourseAiPromptRainRuleTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final CourseAiPrompt prompt = new CourseAiPrompt(mapper);

    @Test
    void 시스템_프롬프트에_비_예보일_규칙과_실내_우선_목표가_있다() {
        String text = prompt.systemInstruction();

        assertThat(text).contains("28. 후보의 weatherFactSetId가 가리키는 weatherFactSets 세트에서 해당 날짜의 precipitationProbability가 "
                + RainyDayRule.PROB_FROM + " 이상이면");
        assertThat(text).contains("candidates[].indoor가 true인 후보를 우선 배치");
        assertThat(text).contains("WANT와 고정 일정은 이 규칙보다 우선한다(규칙 16)");
        assertThat(text).contains("강수확률 " + RainyDayRule.PROB_FROM + "% 이상을 근거로");
        assertThat(text).doesNotContain("%d");   // formatted 누락이면 자리표시자가 그대로 모델에 간다
    }

    @Test
    void 교정_프롬프트에도_같은_규칙이_실린다() {
        CourseAiInputDto input = new CourseAiInputDto("2.0",
                new CourseAiInputDto.TripConstraintDto(LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 21), Transport.RENTAL_CAR),
                new CourseAiInputDto.SoftPreferencesDto(List.of("EAST"), List.of()),
                new CourseAiInputDto.HardConstraintsDto(List.of()), null,
                List.of(candidate("a", "제주민속자연사박물관")), List.of(), List.of(), null);

        String text = prompt.correctionUserPrompt(input, new CourseAiResultDto("2.0", List.of()),
                CourseAiValidationCode.AI_RESULT_TRIP_DATE_MISSING, "날짜 누락");

        assertThat(text).contains("precipitationProbability가 " + RainyDayRule.PROB_FROM + " 이상이면 비 예보일이며");
        assertThat(text).contains("indoor=true 후보를 우선 배치");
        assertThat(text).contains("Validation code: AI_RESULT_TRIP_DATE_MISSING");
        assertThat(text).doesNotContain("%d");
    }

    @Test
    void 후보_JSON에_indoor가_실리고_읽을_때는_무시된다() throws Exception {
        String museum = mapper.writeValueAsString(candidate("a", "제주민속자연사박물관"));
        String peak = mapper.writeValueAsString(candidate("b", "성산일출봉"));

        assertThat(museum).contains("\"indoor\":true");
        assertThat(peak).contains("\"indoor\":false");
        // 진단 기록 등에서 입력 JSON을 다시 읽어도 파생 필드가 걸림돌이 되면 안 된다
        CandidateFactDto back = mapper.readValue(museum, CandidateFactDto.class);
        assertThat(back.candidateId()).isEqualTo("a");
        assertThat(back.indoor()).isTrue();
    }

    private static CandidateFactDto candidate(String id, String name) {
        return new CandidateFactDto(id, name, "EAST", "TOURIST", List.of(), List.of(), null, null);
    }
}
