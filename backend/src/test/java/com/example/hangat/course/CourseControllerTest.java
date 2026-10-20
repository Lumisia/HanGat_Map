package com.example.hangat.course;

import com.example.hangat.course.model.CourseResponseDto;
import com.example.hangat.course.model.enums.CourseStatus;
import com.example.hangat.course.model.enums.CourseType;
import com.example.hangat.course.model.enums.GenerationReason;
import com.example.hangat.course.model.enums.Transport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CourseControllerTest {

    @Test
    void renewalReturnsOnlyProofAndExpiryWithNoStoreAndRequiresBody() throws Exception {
        var service = mock(CourseService.class);
        var claims = mock(CourseClaimService.class);
        when(claims.renew(11L, "proof")).thenReturn(new CourseClaimTokenService.ClaimProof(
                "renewed-proof", java.time.Instant.parse("2026-09-07T05:50:00Z")));
        var mvc = MockMvcBuilders.standaloneSetup(new CourseController(service, claims, mock(CourseClaimTokenService.class)))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper)).build();
        mvc.perform(post("/courses/11/claim/renew").contentType(MediaType.APPLICATION_JSON).content("{\"claim_token\":\"proof\"}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.result.claim_token").value("renewed-proof"))
                .andExpect(jsonPath("$.result.claim_expires_at").value("2026-09-07T05:50:00Z"));
        mvc.perform(post("/courses/11/claim/renew").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        org.mockito.Mockito.verifyNoInteractions(service);
    }

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void postCoursesReturnsSnakeCaseUtf8JsonBody() throws Exception {
        CourseService courseService = mock(CourseService.class);
        CourseClaimService claimService = mock(CourseClaimService.class);
        CourseClaimTokenService tokenService = mock(CourseClaimTokenService.class);
        when(courseService.createCourse(any())).thenReturn(response());
        when(tokenService.issue(101L)).thenReturn(new CourseClaimTokenService.ClaimProof(
                "test-claim-token", java.time.Instant.parse("2026-08-31T12:30:00Z")));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new CourseController(courseService, claimService, tokenService))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();

        MvcResult result = mockMvc.perform(post("/courses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding(StandardCharsets.UTF_8.name())
                        .content("""
                                {
                                  "start_date":"2026-08-27",
                                  "end_date":"2026-08-29",
                                  "people":2,
                                  "transport":"RENTAL_CAR",
                                  "course_regions":[],
                                  "course_styles":[{"code":"NATURE","weight":1}],
                                  "course_place_preferences":[]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.result.contract_version").value("1.0"))
                .andExpect(jsonPath("$.result.claim_token").value("test-claim-token"))
                .andExpect(jsonPath("$.result.claim_expires_at").value("2026-08-31T12:30:00Z"))
                .andExpect(jsonPath("$.result.days[0].day_no").value(1))
                .andExpect(jsonPath("$.result.days[0].visit_date").value("2026-08-28"))
                .andExpect(jsonPath("$.result.days[0].items[0].candidate_id").value("candidate-1"))
                .andExpect(jsonPath("$.result.days[0].items[0].id").value(201))
                .andExpect(jsonPath("$.result.days[0].items[0].course_id").value(101))
                .andExpect(jsonPath("$.result.days[0].items[0].place_id").value(301))
                .andExpect(jsonPath("$.result.days[0].items[0].source_code").value("KTO"))
                .andExpect(jsonPath("$.result.days[0].items[0].source_place_id").value("125266"))
                .andExpect(jsonPath("$.result.days[0].items[0].place_name").value("성산일출봉"))
                .andExpect(jsonPath("$.result.days[0].items[0].day_no").value(1))
                .andExpect(jsonPath("$.result.days[0].items[0].visit_date").value("2026-08-28"))
                .andExpect(jsonPath("$.result.days[0].items[0].category_name").value("관광지"))
                .andExpect(jsonPath("$.result.days[0].items[0].costs").isArray())
                .andExpect(jsonPath("$.result.days[0].items[0].costs").isEmpty())
                .andExpect(jsonPath("$.result.budget_summary.has_cost_data").value(false))
                .andExpect(jsonPath("$.result.budget_total").doesNotExist())
                .andExpect(jsonPath("$.result.budget_summary.budget_total").doesNotExist())
                .andExpect(jsonPath("$.result.budget_summary.total_expected").isEmpty())
                .andExpect(jsonPath("$.result.budget_summary.over_budget").doesNotExist())
                .andExpect(jsonPath("$.result.days[0].items[0].congestion_rate").value(22.5))
                .andExpect(jsonPath("$.result.days[0].items[0].congestion_level").value("QUIET"))
                .andExpect(jsonPath("$.result.accommodation.place_name").value("제주 숙소"))
                .andExpect(jsonPath("$.result.days[0].items[0].recommendation_reason")
                        .value("혼잡도가 낮고 동선이 좋아요."))
                .andExpect(jsonPath("$.result.days[0].items[0].congestion[0].rate").value(22.5))
                .andExpect(jsonPath("$.result.days[0].items[0].weather[0].forecast_date")
                        .value("2026-08-28"))
                .andReturn();

        String responseBody = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(responseBody).contains("성산일출봉", "혼잡도가 낮고 동선이 좋아요.");
        assertThat(objectMapper.readTree(responseBody)
                .path("result").path("days").isArray()).isTrue();
    }

    @Test
    void authenticatedCreationDoesNotIssueGuestClaimProof() {
        CourseService courseService = mock(CourseService.class);
        CourseClaimService claimService = mock(CourseClaimService.class);
        CourseClaimTokenService tokenService = mock(CourseClaimTokenService.class);
        when(courseService.createCourse(any())).thenReturn(response());
        CourseController controller = new CourseController(courseService, claimService, tokenService);

        CourseResponseDto response = controller.createCourse(
                mock(com.example.hangat.course.model.CourseRequestDto.class),
                new UsernamePasswordAuthenticationToken(7L, null, List.of()))
                .getResult();

        assertThat(response.claimToken()).isNull();
        org.mockito.Mockito.verifyNoInteractions(tokenService);
    }

    private CourseResponseDto response() {
        com.example.hangat.course.model.AccommodationDto accommodation;
        try {
            accommodation = objectMapper.readValue("""
                    {"source_code":"KAKAO_LOCAL","source_place_id":"stay-1",
                     "place_name":"제주 숙소","address":"제주 주소",
                     "latitude":33.4,"longitude":126.8}
                    """, com.example.hangat.course.model.AccommodationDto.class);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        CourseResponseDto.ItemDto item = new CourseResponseDto.ItemDto(
                201L, 101L, 301L,
                "candidate-1", "KTO", "125266",
                "성산일출봉", "제주특별자치도 서귀포시 성산읍", null,
                33.458, 126.942, null,
                "관광지",
                "EAST", null, List.of("NATURE"), 1, 1,
                LocalDate.of(2026, 8, 28),
                LocalTime.of(9, 0), CourseResponseDto.ItemSource.AI_RECOMMENDED,
                "혼잡도가 낮고 동선이 좋아요.",
                List.of(),
                null,
                null,
                new BigDecimal("22.5"),
                com.example.hangat.map.model.enums.CongestionLevel.QUIET,
                List.of(new CourseResponseDto.CongestionFactDto(
                        LocalDate.of(2026, 8, 28), new BigDecimal("22.5"),
                        com.example.hangat.map.model.enums.CongestionLevel.QUIET)),
                List.of(new CourseResponseDto.WeatherFactDto(
                        LocalDate.of(2026, 8, 28), LocalTime.of(9, 0),
                        new BigDecimal("27.5"), 20, "0", "1",
                        new BigDecimal("2.1"), 65)));
        return new CourseResponseDto(
                101L, "1.0", CourseType.USER, GenerationReason.INITIAL, CourseStatus.READY,
                LocalDate.of(2026, 8, 27), LocalDate.of(2026, 8, 29),
                (short) 2,  Transport.RENTAL_CAR, accommodation,
                null, null,
                new CourseResponseDto.BudgetSummaryDto(
                        false, 0, null, null, null, null, null, null, 0),
                List.of(new CourseResponseDto.DayDto(
                        1, LocalDate.of(2026, 8, 28), List.of(item))));
    }
}
