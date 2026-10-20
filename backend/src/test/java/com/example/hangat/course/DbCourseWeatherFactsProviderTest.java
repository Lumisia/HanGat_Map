package com.example.hangat.course;

import com.example.hangat.course.model.*;
import com.example.hangat.course.travel.*;
import com.example.hangat.domain.weather.model.entity.WeatherForecast;
import com.example.hangat.domain.weather.model.enums.WeatherGranularity;
import com.example.hangat.domain.weather.repository.WeatherForecastRepository;
import com.example.hangat.map.model.entity.*;
import com.example.hangat.map.repository.RegionRepository;
import com.example.hangat.map.service.PlaceNameNormalizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class DbCourseWeatherFactsProviderTest {
    final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    final WeatherForecastRepository repository = mock(WeatherForecastRepository.class);
    final RegionRepository regions = mock(RegionRepository.class);
    final Clock clock = Clock.fixed(Instant.parse("2026-09-07T15:00:00Z"), ZoneOffset.UTC);
    final Region east = Region.builder().id((short)2).code("EAST").name("동부").kmaGridX((short)59).kmaGridY((short)38).build();
    final DbCourseWeatherFactsProvider provider = new DbCourseWeatherFactsProvider(repository, regions, clock);
    CourseRequestDto request() throws Exception {
        return json.readValue("""
            {"start_date":"2026-09-11","end_date":"2026-09-13","people":2,"transport":"PUBLIC_TRANSIT","course_regions":[{"code":"EAST"}],"course_styles":[{"code":"NATURE"}]}
            """, CourseRequestDto.class);
    }
    List<CourseCandidateDto> candidates() throws Exception {
        var p=json.readValue("""
            {"contentid":"123","title":"장소","addr1":"제주특별자치도 제주시 구좌읍","mapy":33.5,"mapx":126.8,"cat1":"A01"}
            """, TourPlaceDto.class);
        return List.of(new CourseCandidateDto(p,List.of(),null,List.of()));
    }
    WeatherForecast row(String day, LocalDateTime base) {
        return WeatherForecast.daily(east, DataSource.builder().code("KMA_MID").build(),
                PlaceNameNormalizer.jejuDayToUtc(LocalDate.parse(day)),base,"흐림",null,23,29,60);
    }
    @Test void requestedDatesAndActualRegionReachAiWithDailyProvenance() throws Exception {
        when(regions.findAll()).thenReturn(List.of(east));
        when(repository.findLatestPerDate(eq((short)2),any(),any(),eq(WeatherGranularity.DAILY)))
                .thenReturn(List.of(row("2026-09-11",LocalDateTime.of(2026,9,7,9,0))));
        var service=new CourseAiPreparationService(new CourseAiInputAssembler(),
                new CourseTravelService(new StraightLineDistanceCalculator()),Optional.of(provider));
        var input=service.prepare(request(),candidates());
        verify(repository).findLatestPerDate((short)2, LocalDateTime.of(2026,9,10,15,0),
                LocalDateTime.of(2026,9,12,15,0),WeatherGranularity.DAILY);
        assertThat(input.candidates().get(0).weatherFactSetId()).isEqualTo("db-weather-EAST");
        var fact=input.weatherFactSets().get(0).facts().get(0);
        assertThat(fact.forecastDate()).isEqualTo(LocalDate.of(2026,9,11));
        assertThat(fact.forecastTime()).isNull(); assertThat(fact.temperature()).isNull();
        assertThat(fact.dailyEvidence().spatialScope()).isEqualTo("JEJU_ISLAND");
        assertThat(fact.dailyEvidence().tempMin()).isEqualByComparingTo("23");
        verifyNoMoreInteractions(repository);
    }
    @Test void staleOutOfTripFutureAndUnknownRegionDoNotInventFacts() throws Exception {
        when(regions.findAll()).thenReturn(List.of(east));
        when(repository.findLatestPerDate(any(),any(),any(),any())).thenReturn(List.of(
                row("2026-09-11",LocalDateTime.of(2026,9,6,2,59)),
                row("2026-09-10",LocalDateTime.of(2026,9,7,9,0)),
                row("2026-09-12",LocalDateTime.of(2026,9,8,9,0))));
        assertThat(provider.load(request(),candidates()).weatherFactSets()).isEmpty();
        reset(repository);
        when(regions.findAll()).thenReturn(List.of(Region.builder().id((short)1).code("NORTH").kmaGridX((short)52).kmaGridY((short)38).build()));
        assertThat(provider.load(request(),candidates()).weatherFactSets()).isEmpty();
        verifyNoInteractions(repository);
    }
    @Test void kstMidnightFreshnessBoundaryAndDbFailureRemainOptional() throws Exception {
        when(regions.findAll()).thenReturn(List.of(east));
        when(repository.findLatestPerDate(any(),any(),any(),any())).thenReturn(List.of(row("2026-09-11",LocalDateTime.of(2026,9,6,3,0))));
        assertThat(provider.load(request(),candidates()).weatherFactSets()).hasSize(1);
        when(repository.findLatestPerDate(any(),any(),any(),any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("unavailable"));
        assertThat(provider.load(request(),candidates()).weatherFactSets()).isEmpty();
    }
    @Test void detailReaderUsesSameRulesAndRereadsLatestPublicationWithoutSnapshot() throws Exception {
        when(regions.findAll()).thenReturn(List.of(east));
        var first = row("2026-09-11", LocalDateTime.of(2026,9,7,6,0));
        var newer = row("2026-09-11", LocalDateTime.of(2026,9,7,9,0));
        when(repository.findLatestPerDate(any(),any(),any(),any())).thenReturn(List.of(first), List.of(newer));
        var start = request().getStartDate(); var end = request().getEndDate();
        var before = provider.loadDates(start, end, Set.of("EAST"));
        var after = provider.loadDates(start, end, Set.of("EAST"));
        assertThat(before.weatherFactSets().get(0).facts().get(0).dailyEvidence().issuedAtUtc()).isEqualTo(first.getBaseAt());
        assertThat(after.weatherFactSets().get(0).facts().get(0).dailyEvidence().issuedAtUtc()).isEqualTo(newer.getBaseAt());
        verify(repository, times(2)).findLatestPerDate((short)2, PlaceNameNormalizer.jejuDayToUtc(start),
                PlaceNameNormalizer.jejuDayToUtc(end), WeatherGranularity.DAILY);
        assertThat(provider.loadDates(start, end, Set.of("WEST")).weatherFactSets()).isEmpty();
        verifyNoMoreInteractions(repository);
    }
}
