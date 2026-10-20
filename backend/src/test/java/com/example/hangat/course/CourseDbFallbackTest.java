package com.example.hangat.course;

import com.example.hangat.course.ai.*;
import com.example.hangat.course.facts.*;
import com.example.hangat.course.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.example.hangat.common.exception.GlobalExceptionHandler;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CourseDbFallbackTest {
    final ObjectMapper mapper=new ObjectMapper().registerModule(new JavaTimeModule());
    final TourApiService kto=mock(TourApiService.class);
    final CongestionApiService congestion=mock(CongestionApiService.class);
    final CourseDbCandidateService db=mock(CourseDbCandidateService.class);
    final CourseAiPreparationService prep=mock(CourseAiPreparationService.class);
    final CourseAiGenerationService ai=mock(CourseAiGenerationService.class);
    final CoursePersistenceService persistence=mock(CoursePersistenceService.class);
    final CourseBudgetService budget=mock(CourseBudgetService.class);
    final CourseResponseAssembler response=mock(CourseResponseAssembler.class);
    final CourseService service=new CourseService(kto,congestion,new CourseCandidateShortlistService(),prep,ai,persistence,budget,response,Optional.of(db));
    CourseRequestDto request()throws Exception{return mapper.readValue("""
        {"start_date":"2026-09-07","end_date":"2026-09-09","people":2,"transport":"PUBLIC_TRANSIT",
         "course_regions":[],"course_styles":[{"code":"NATURE"}]}
        """,CourseRequestDto.class);}
    CourseCandidateDto fact(int i){return CourseCandidateDto.fromStored(new CourseCandidate(new CandidateIdentity("db"+i,(long)i+1,"KTO","db"+i),
            new PlaceFact("same name",null,null,new BigDecimal("33.4"),new BigDecimal("126.6"),null),UserConstraint.none(),"EAST",List.of(),
            new InternalPlaceCategory(1L,"TOURIST","Tourist"),List.of(new StyleHint("NATURE","KTO_CLASSIFICATION","NA010200")),List.of(),null));}
    void ready(){when(prep.prepareGeneration(any(),anyList())).thenReturn(new CourseAiPreparationService.PreparedGeneration(null,mock(CourseAiInputDto.class),null));}
    @Test void enoughDbSkipsKtoAndCongestionApi()throws Exception{
        ready();var facts=java.util.stream.IntStream.range(0,15).mapToObj(this::fact).toList();when(db.find(any())).thenReturn(facts);
        assertThat(service.prepareAiInput(request())).isNotNull();verifyNoInteractions(kto,congestion,ai,persistence);verify(prep).prepareGeneration(any(),eq(facts));
    }
    @Test void enoughOrdinaryCandidatesStillSupplementsMissingCafeWithoutExceedingLimit()throws Exception {
        ready();when(db.find(any())).thenReturn(java.util.stream.IntStream.range(0,15).mapToObj(this::fact).toList());
        var tree=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(request());
        tree.putArray("course_styles").addObject().put("code","CAFE");
        var req=mapper.treeToValue(tree,CourseRequestDto.class);
        when(kto.getTourPlaces()).thenReturn(List.of(mapper.readValue("{\"contentid\":\"cafe\",\"title\":\"카페\",\"addr1\":\"제주시\",\"cat3\":\"A05020900\"}",TourPlaceDto.class)));
        when(congestion.getCongestionData(any(),any())).thenReturn(List.of());
        service.prepareAiInput(req);
        verify(prep).prepareGeneration(any(),argThat(c->c.size()==15 && c.stream().anyMatch(x->"cafe".equals(x.getPlace().getContentId()))));
    }
    @Test void shortageMakesOneLogicalCallAndDeduplicatesSourceIdentity()throws Exception{
        ready();when(db.find(any())).thenReturn(List.of(fact(0)));
        when(kto.getTourPlaces()).thenReturn(List.of(tour("db0"),tour("new1"),tour("new2")));
        service.prepareAiInput(request());verify(kto,times(1)).getTourPlaces();
        verify(prep).prepareGeneration(any(),argThat(c->c.size()==3&&c.stream().filter(x->x.getPlace().getContentId().equals("db0")).count()==1));
        verifyNoInteractions(ai,persistence);
    }
    @Test void insufficientDbAndTimeoutStopsBeforeAiAndPersistence()throws Exception{
        when(db.find(any())).thenReturn(List.of(fact(0)));when(kto.getTourPlaces()).thenThrow(new KtoApiException(true));
        assertUnavailable(request());
        verify(kto,times(1)).getTourPlaces();verifyNoInteractions(prep,ai,persistence,budget,response);
    }
    @Test void dbMeetingExistingNonEmptyDayMinimumSurvivesTemporaryFailure()throws Exception{
        ready();when(db.find(any())).thenReturn(List.of(fact(0),fact(1),fact(2)));when(kto.getTourPlaces()).thenThrow(new KtoApiException(true));
        assertThat(service.prepareAiInput(request())).isNotNull();verify(prep).prepareGeneration(any(),argThat(c->c.size()==3));
    }
    @Test void emptyFallbackIsUnavailableNotAnEmptySuccessfulCourse()throws Exception{
        when(db.find(any())).thenReturn(List.of());when(kto.getTourPlaces()).thenReturn(List.of());
        assertUnavailable(request());
        verifyNoInteractions(congestion);
    }
    @Test void regionFilteredEmptyFallbackReturnsSafe503()throws Exception{
        var json=mapper.valueToTree(request());
        ((com.fasterxml.jackson.databind.node.ObjectNode)json).putArray("course_regions").addObject().put("code","EAST");
        var east=mapper.treeToValue(json,CourseRequestDto.class);
        when(db.find(any())).thenReturn(List.of());
        when(kto.getTourPlaces()).thenReturn(List.of(tour("outside-region")));
        assertUnavailable(east);
        verify(kto,times(1)).getTourPlaces();verifyNoInteractions(congestion);
    }
    @Test void partialDbAndSupplementStillBelowMinimumReturnsSafe503()throws Exception{
        when(db.find(any())).thenReturn(List.of(fact(0)));
        when(kto.getTourPlaces()).thenReturn(List.of(tour("new1")));
        assertUnavailable(request());
        verify(kto,times(1)).getTourPlaces();
    }
    @Test void successfulSupplementGeneratesAndPersistsOnlyOnce()throws Exception{
        ready();successfulResult();when(db.find(any())).thenReturn(List.of(fact(0)));
        when(kto.getTourPlaces()).thenReturn(List.of(tour("new1"),tour("new2")));
        assertThat(service.createCourse(request())).isNotNull();
        verify(kto,times(1)).getTourPlaces();
        verify(prep).prepareGeneration(any(),argThat(c->c.size()==3));
        verify(ai,times(1)).generate(any());
        verify(persistence,times(1)).persist(any(),any(),any(),any());
    }
    @Test void successfulDbFlowPersistsOnlyOnce()throws Exception{
        ready();when(db.find(any())).thenReturn(java.util.stream.IntStream.range(0,15).mapToObj(this::fact).toList());
        successfulResult();
        assertThat(service.createCourse(request())).isNotNull();
        verify(ai,times(1)).generate(any());
        verify(persistence,times(1)).persist(any(),any(),any(),any());verifyNoInteractions(kto,congestion);
    }
    void successfulResult(){
        when(ai.generate(any())).thenReturn(mock(CourseAiResultDto.class));
        var course=mock(com.example.hangat.course.model.entity.Course.class);when(course.getId()).thenReturn(42L);
        when(persistence.persist(any(),any(),any(),any())).thenReturn(new CoursePersistenceResult(course,Map.of(),Map.of()));
        when(response.assemble(any(),any(),any(),any(),any())).thenReturn(mock(CourseResponseDto.class));
    }
    void assertUnavailable(CourseRequestDto request)throws Exception{
        var claim=mock(CourseClaimService.class);var token=mock(CourseClaimTokenService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new CourseController(service,claim,token))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/courses").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(5002))
                .andExpect(jsonPath("$.message").value(KtoApiException.USER_MESSAGE))
                .andExpect(jsonPath("$.result").doesNotExist());
        // Course/Item and any new Place/Mapping writes are owned by persistence, never reached here.
        verifyNoInteractions(prep,ai,persistence,budget,response,claim,token);
    }
    TourPlaceDto tour(String id)throws Exception{return mapper.readValue("{\"contentid\":\""+id+"\",\"title\":\"same name\",\"cat1\":\"A01\",\"mapy\":33.4,\"mapx\":126.6}",TourPlaceDto.class);}
}
