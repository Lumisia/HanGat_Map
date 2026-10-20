package com.example.hangat.course.ai;

import com.example.hangat.course.service.RainyDayRule;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class CourseAiPrompt {

    static final String SYSTEM_INSTRUCTION = """
            너는 제주 여행 코스 생성 엔진이다. 백엔드가 제공한 후보와 사실 데이터만 사용한다.

            절대 규칙:
            1. candidates 목록에 없는 장소를 선택하지 않는다.
            2. 응답 candidateId는 반드시 candidates[].candidateId 중 하나를 문자 하나도 변경하지 않고 그대로 사용한다. 새 ID를 생성하거나 다른 ID로 해석하지 않는다.
            3. 모든 WANT 장소를 정확히 한 번 포함한다.
            4. AVOID 장소는 절대 포함하지 않는다.
            5. fixedDate를 변경하지 않는다.
            6. fixedTime을 변경하지 않는다.
            7. 여행기간 밖 날짜를 생성하지 않는다.
            8. 하나의 candidateId는 전체 여행 일정에서 최대 1회만 사용한다. 날짜나 시간이 달라도 같은 candidateId를 다시 사용하지 않는다. WANT 장소도 정확히 1회만 배치한다.
            9. 후보가 부족하면 같은 candidateId로 일정을 채우지 말고 더 적은 장소를 선택한다.
            10. 제공되지 않은 혼잡도 숫자나 단계를 생성하지 않는다.
            11. 제공되지 않은 날씨를 생성하지 않는다.
            12. 제공되지 않은 routeDistanceMeters 또는 travelMinutes를 생성하거나 추정하지 않는다.
            13. straightDistanceMeters는 직선거리이며 도로거리나 이동시간으로 해석하지 않는다.
            14. 스타일은 Hard Filter가 아닌 Soft Preference다.
            15. 혼잡도, 날씨, 거리도 Soft 판단 데이터다.
            16. WANT와 고정 일정은 모든 Soft Preference보다 우선한다.
            17. 지역 조건은 백엔드가 이미 적용했으므로 제공된 candidates만 사용한다.
            18. 추천 이유는 입력 JSON에서 실제로 확인할 수 있는 근거만 사용한다.
            19. days는 날짜 오름차순으로 작성하고 각 DAY에는 한 개 이상의 장소를 포함한다.
            20. 후보가 충분하고 Hard Constraint와 사실 데이터가 허용하면 각 DAY에 기본적으로 3개 장소를 배치한다.
            21. 후보 부족이나 Hard Constraint 충돌로 3개 배치가 불가능하면 중복 없이 2개를 배치하고, 2개도 불가능한 경우에만 1개를 허용한다.
            22. 고정 시간이 없는 장소는 가능한 경우 10:00:00, 14:00:00, 18:00:00을 고려하되 fixedDate와 fixedTime을 절대 덮어쓰지 않는다.
            23. 각 DAY의 items는 startTime 오름차순이며 같은 시작 시간을 중복 사용하지 않는다.
            24. recommendationReason은 한 줄 근거로 작성하며 300자를 초과하지 않는다.
            25. 선택 스타일과 후보 styleHintCodes를 장소 선택과 체류시간에 반영한다.
            26. travelMinutes는 생성 단계의 추정 이동시간일 수 있으므로, 실제 경로값으로 설명하지 않고 체류시간과 함께 일정 간격 검증에만 사용한다.
            27. generationMetadata.generationReason이 USER_REGENERATE이면 Hard Constraint를 유지하면서 INITIAL과 다른 후보 또는 방문 순서를 우선한다. 사실상 다른 구성이 불가능하면 조건을 완화하거나 장소를 만들지 않는다.
            28. 후보의 weatherFactSetId가 가리키는 weatherFactSets 세트에서 해당 날짜의 precipitationProbability가 %d 이상이면 그 날짜는 그 후보에게 비 예보일이다. 강수확률이 제공되지 않은 날짜는 비 예보일로 보지 않는다.

            응답의 startTime은 반드시 제주 현지 시각의 24시간제 HH:mm:ss 형식으로 작성한다.
            startTime에 timezone, UTC offset, Z, fractional seconds를 포함하지 않는다.

            travelFacts 규칙:
            - sparse adjacent pair일 수 있으며 배열 순서는 최종 방문 순서가 아니다.
            - pair가 있다는 사실은 두 장소를 반드시 방문하라는 뜻이 아니다.
            - routeDistanceMeters 또는 travelMinutes가 null이면 실제 경로거리 또는 이동시간 정보가 없는 것이다.
            - null인 사실값을 추정하지 않는다.

            weatherFactSets 규칙:
            - 후보의 weatherFactSetId로 최상위 weatherFactSets를 참조한다.
            - weatherFactSetId가 null이면 해당 후보의 날씨가 제공되지 않은 것이다.
            - 제공되지 않은 날짜나 시간의 날씨를 추정하지 않는다.

            목표:
            - DAY별 코스를 만들고 하루 동선의 불필요한 왕복을 줄인다.
            - 가능한 경우 혼잡도가 낮은 장소와 시간을 고려한다.
            - 선택 스타일을 전체 일정에서 고르게 반영한다.
            - 고정 일정 전후에 적절한 주변 일정을 배치한다.
            - 비 예보일(규칙 28)에는 candidates[].indoor가 true인 후보를 우선 배치하고 실외 장소 수를 최소화한다. 단 WANT와 고정 일정은 이 규칙보다 우선한다(규칙 16). 비 예보 때문에 실내 후보를 고른 경우 recommendationReason에 그 날짜의 강수확률 %d%% 이상을 근거로 적는다.
            - 응답 스키마에 정의된 값만 JSON으로 반환한다.
            """.formatted(RainyDayRule.PROB_FROM, RainyDayRule.PROB_FROM);

    private final ObjectMapper objectMapper;

    public CourseAiPrompt(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String systemInstruction() {
        return SYSTEM_INSTRUCTION;
    }

    public String userPrompt(CourseAiInputDto input) {
        if (input == null) {
            throw new CourseAiException(
                    CourseAiFailureType.VALIDATION_ERROR,
                    "Gemini에 전달할 AI 코스 입력이 필요합니다."
            );
        }

        try {
            return objectMapper.writeValueAsString(input);
        } catch (JsonProcessingException exception) {
            throw new CourseAiException(
                    CourseAiFailureType.INVALID_RESPONSE,
                    "AI 코스 입력을 JSON으로 변환할 수 없습니다."
            );
        }
    }

    public String correctionUserPrompt(
            CourseAiInputDto input,
            CourseAiResultDto previousResult,
            CourseAiValidationCode validationCode,
            String validationMessage
    ) {
        String inputJson = userPrompt(input);
        String previousResultJson = resultJson(previousResult);
        String code = validationCode == null
                ? CourseAiValidationCode.AI_RESULT_INPUT_INVALID.name()
                : validationCode.name();
        String message = validationMessage == null || validationMessage.isBlank()
                ? "AI 출력 계약 위반"
                : validationMessage;
        return """
                이전 응답이 백엔드 검증에 실패했다. 아래 실패 사유를 교정하여 새 결과 전체를 반환한다.
                Validation code: %s
                Validation message: %s

                교정 절대 규칙:
                - 하나의 candidateId는 전체 여행 일정에서 최대 1회만 사용한다.
                - 날짜나 시간이 달라도 동일 candidateId를 재사용하지 않는다.
                - WANT 장소는 정확히 1회만 배치한다.
                - 후보가 부족하면 중복해서 채우지 말고 더 적은 장소를 선택한다.
                - candidateId는 아래 입력 candidates[].candidateId 중 하나를 그대로 사용한다.
                - 후보가 충분하고 Hard Constraint와 사실 데이터가 허용하면 각 DAY에 기본적으로 3개 장소를 배치한다.
                - 3개가 불가능하면 중복 없이 2개를 배치하고, 2개도 불가능한 경우에만 1개를 허용한다.
                - 고정 시간이 없는 장소는 가능한 경우 10:00:00, 14:00:00, 18:00:00을 고려하되 fixedDate와 fixedTime을 덮어쓰지 않는다.
                - days와 각 DAY의 items는 각각 날짜와 startTime 오름차순으로 작성한다.
                - startTime은 제주 현지 시각의 HH:mm:ss 형식만 사용한다.
                - startTime에 timezone, UTC offset, Z, fractional seconds를 포함하지 않는다.
                - recommendationReason은 입력 사실에 근거한 한 줄 문장으로 300자를 넘지 않는다.
                - 후보 weatherFactSetId 세트의 그 날짜 precipitationProbability가 %d 이상이면 비 예보일이며, 그 날짜에는 indoor=true 후보를 우선 배치한다. WANT와 고정 일정은 이보다 우선한다.

                이전 전체 결과 JSON:
                %s

                원본 입력 JSON:
                %s
                """.formatted(code, message, RainyDayRule.PROB_FROM, previousResultJson, inputJson);
    }

    private String resultJson(CourseAiResultDto result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new CourseAiException(
                    CourseAiFailureType.INVALID_RESPONSE,
                    "이전 AI 코스 결과를 교정 요청 JSON으로 변환할 수 없습니다."
            );
        }
    }
}
