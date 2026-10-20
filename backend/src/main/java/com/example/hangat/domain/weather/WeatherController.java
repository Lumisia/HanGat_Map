package com.example.hangat.domain.weather;

import com.example.hangat.common.model.BaseResponse;
import com.example.hangat.domain.weather.model.DailyWeather;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/main")
public class WeatherController {

    private final WeatherService weatherService;

    public WeatherController(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    @GetMapping("/weather")
    @Operation(summary = "주간 날씨 조회", description = """
            제주 7일 예보(오늘부터, 날짜순). region(NORTH·EAST·SOUTH·WEST)을 주면 그 권역 격자의 적재분을,
            없으면 기존대로 북부(제주시 격자)를 읽는다. 적재 전이면 북부만 기상청 단기(D+0~3)·중기(D+4~6)를 직접 병합해 돌려준다.
            값이 없는 날짜는 null, 모르는 권역 코드는 빈 목록이다. issuedAt은 적재분의 기상청 발표 시각(KST).""")
    public BaseResponse<List<DailyWeather>> weeklyWeather(
            @RequestParam(name = "region", required = false) String region) {
        // 지도(MAP_006)는 장소 권역별로 부른다 - 남부 관광지에 제주시 날씨를 보여주지 않기 위해
        return BaseResponse.success(region == null || region.isBlank()
                ? weatherService.getWeeklyForecast()
                : weatherService.getWeeklyForecast(region.trim().toUpperCase()));
    }
}
