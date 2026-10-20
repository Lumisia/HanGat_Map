package com.example.hangat.config;

import com.example.hangat.course.CourseService;
import com.example.hangat.course.model.CourseResponseDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecuritySmokeTests {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    CourseService courseService;

    @BeforeEach
    void stubPublicCourseCreation() {
        CourseResponseDto response = mock(CourseResponseDto.class);
        when(response.id()).thenReturn(1L);
        when(response.withClaimProof(any(), any())).thenReturn(response);
        when(courseService.createCourse(any())).thenReturn(response);
    }

    @Test
    void 헬스체크는_인증_없이_접근된다() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void 스웨거_문서는_인증_없이_접근된다() throws Exception {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }

    @Test
    void 코스_생성은_인증_없이_접근된다() throws Exception {
        mockMvc.perform(post("/courses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"start_date":"2026-09-10","end_date":"2026-09-12",
                                 "people":2,"transport":"RENTAL_CAR",
                                 "course_regions":[],
                                 "course_styles":[{"code":"NATURE","weight":1}],
                                 "course_place_preferences":[]}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void 프론트_개발_서버_4173의_코스_생성_preflight를_허용한다() throws Exception {
        mockMvc.perform(options("/courses")
                        .header("Origin", "http://localhost:4173")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:4173"));
    }

    @Test
    void 사용자_조회는_계속_인증이_필요하다() throws Exception {
        mockMvc.perform(get("/users/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void 코스_claim은_인증이_필요하다() throws Exception {
        mockMvc.perform(post("/courses/1/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"claim_token\":\"proof\",\"title\":\"제주 여행\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 코스_갱신은_회원인증_대신_코스증명을_요구한다() throws Exception {
        mockMvc.perform(post("/courses/1/claim/renew")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"claim_token\":\"invalid-proof\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(3303))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }
}
