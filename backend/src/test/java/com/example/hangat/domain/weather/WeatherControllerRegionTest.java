package com.example.hangat.domain.weather;

import com.example.hangat.domain.weather.model.DailyWeather;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /main/weather 의 region 파라미터 - 있으면 그 권역, 없으면 기존(북부) 경로. 응답에 발표 시각이 실린다 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WeatherControllerRegionTest {

    @Autowired
    MockMvc mockMvc;
    @MockitoBean
    WeatherService weatherService;

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 11);
    private static final LocalDateTime ISSUED = LocalDateTime.of(2026, 9, 11, 5, 0);

    @Test
    void region_파라미터가_있으면_그_권역으로_조회하고_발표_시각을_싣는다() throws Exception {
        when(weatherService.getWeeklyForecast("SOUTH"))
                .thenReturn(List.of(new DailyWeather(TODAY, 22, 27, "맑음", 10, ISSUED)));

        mockMvc.perform(get("/main/weather").param("region", "south"))   // 소문자도 받는다
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result[0].sky").value("맑음"))
                .andExpect(jsonPath("$.result[0].issuedAt").value("2026-09-11T05:00:00"));

        verify(weatherService).getWeeklyForecast("SOUTH");
        verify(weatherService, never()).getWeeklyForecast();
    }

    @Test
    void region_이_없으면_기존_경로_그대로다() throws Exception {
        when(weatherService.getWeeklyForecast())
                .thenReturn(List.of(new DailyWeather(TODAY, 22, 27, "맑음", 10)));

        mockMvc.perform(get("/main/weather"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result[0].sky").value("맑음"));

        verify(weatherService).getWeeklyForecast();
        verify(weatherService, never()).getWeeklyForecast(anyString());
    }
}
