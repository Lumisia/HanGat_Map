package com.example.hangat.course;

import com.example.hangat.course.ai.CourseAiGenerationService;
import com.example.hangat.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class KtoFailureFlowTest {
    @Test void realCourseOrchestrationStopsBeforeGeminiAndAllPersistence() throws Exception {
        var kto=mock(TourApiService.class);
        when(kto.getTourPlaces()).thenThrow(new KtoApiException(true));
        var congestion=mock(CongestionApiService.class); var shortlist=mock(CourseCandidateShortlistService.class);
        var preparation=mock(CourseAiPreparationService.class);var gemini=mock(CourseAiGenerationService.class);
        var persistence=mock(CoursePersistenceService.class); var budget=mock(CourseBudgetService.class);
        var assembler=mock(CourseResponseAssembler.class); var token=mock(CourseClaimTokenService.class);
        var service=new CourseService(kto,congestion,shortlist,preparation,gemini,persistence,budget,assembler);
        var mvc=MockMvcBuilders.standaloneSetup(new CourseController(service,mock(CourseClaimService.class),token))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/courses").contentType(MediaType.APPLICATION_JSON).content("""
          {"start_date":"2026-09-06","end_date":"2026-09-08","people":2,"transport":"PUBLIC_TRANSIT","course_regions":[],"course_styles":[{"code":"NATURE","weight":1}],"course_place_preferences":[]}
          """))
          .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(5002))
          .andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.message").value(KtoApiException.USER_MESSAGE))
          .andExpect(jsonPath("$.result").doesNotExist());
        verify(kto).getTourPlaces();
        verifyNoInteractions(congestion,shortlist,preparation,gemini,persistence,budget,assembler,token);
    }
}
