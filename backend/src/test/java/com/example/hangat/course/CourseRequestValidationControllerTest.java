package com.example.hangat.course;

import com.example.hangat.common.exception.GlobalExceptionHandler;
import com.example.hangat.course.model.CourseResponseDto;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CourseRequestValidationControllerTest {

    @Test
    void courseStyles가_누락되면_공통_400을_반환한다() throws Exception {
        Fixture fixture = fixture();

        fixture.mockMvc.perform(post("/courses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest().replace(
                                "\"course_styles\":[{\"code\":\"NATURE\",\"weight\":1}],", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(3000))
                .andExpect(jsonPath("$.result.courseStyles")
                        .value("여행 스타일을 하나 이상 선택해주세요."));

        verify(fixture.courseService, never()).createCourse(any());
    }

    @Test
    void courseStyles가_빈_배열이면_공통_400을_반환한다() throws Exception {
        assertRequestError(validRequest().replace(
                "[{\"code\":\"NATURE\",\"weight\":1}]", "[]"));
    }

    @Test
    void null_style_원소는_공통_400을_반환한다() throws Exception {
        assertRequestError(validRequest().replace(
                "[{\"code\":\"NATURE\",\"weight\":1}]", "[null]"));
    }

    @Test
    void 빈_style_code는_공통_400을_반환한다() throws Exception {
        assertRequestError(validRequest().replace("NATURE", ""));
    }

    @Test
    void 지원하지_않는_style_code는_공통_400을_반환한다() throws Exception {
        assertRequestError(validRequest().replace("NATURE", "ALIEN"));
    }

    @Test
    void 잘못된_transport_enum은_공통_400을_반환한다() throws Exception {
        assertRequestError(validRequest().replace("RENTAL_CAR", "AIRPLANE"));
    }

    @Test
    void null_transport는_공통_400을_반환한다() throws Exception {
        assertRequestError(validRequest().replace("\"RENTAL_CAR\"", "null"));
    }

    @Test
    void 정상_style은_기존_생성_흐름에_진입한다() throws Exception {
        Fixture fixture = fixture();

        fixture.mockMvc.perform(post("/courses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isOk());

        verify(fixture.courseService).createCourse(any());
    }

    @Test
    void 내부_서버_오류를_요청_오류로_변환하지_않는다() {
        Fixture fixture = fixture();
        when(fixture.courseService.createCourse(any()))
                .thenThrow(new IllegalStateException("internal failure"));

        assertThatThrownBy(() -> fixture.mockMvc.perform(post("/courses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest())))
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    private void assertRequestError(String request) throws Exception {
        Fixture fixture = fixture();

        fixture.mockMvc.perform(post("/courses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(3000));

        verify(fixture.courseService, never()).createCourse(any());
    }

    private Fixture fixture() {
        CourseService courseService = mock(CourseService.class);
        CourseClaimService claimService = mock(CourseClaimService.class);
        CourseClaimTokenService tokenService = mock(CourseClaimTokenService.class);
        CourseResponseDto response = mock(CourseResponseDto.class);
        when(response.id()).thenReturn(1L);
        when(response.withClaimProof(any(), any())).thenReturn(response);
        when(courseService.createCourse(any())).thenReturn(response);
        when(tokenService.issue(1L)).thenReturn(new CourseClaimTokenService.ClaimProof(
                "claim-proof", java.time.Instant.parse("2026-09-01T00:00:00Z")));

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new CourseController(
                        courseService, claimService, tokenService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
        return new Fixture(mockMvc, courseService);
    }

    private String validRequest() {
        return """
                {"start_date":"2026-09-10","end_date":"2026-09-12","people":2,
                 "transport":"RENTAL_CAR","course_regions":[],
                 "course_styles":[{"code":"NATURE","weight":1}],
                 "course_place_preferences":[]}
                """;
    }

    private record Fixture(MockMvc mockMvc, CourseService courseService) {
    }
}
