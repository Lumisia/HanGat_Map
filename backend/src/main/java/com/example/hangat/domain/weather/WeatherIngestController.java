package com.example.hangat.domain.weather;

import com.example.hangat.common.model.BaseResponse;
import com.example.hangat.domain.weather.WeatherIngestService.WeatherIngestResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 날씨 적재 수동 실행 - <b>개발 전용 도구</b>.
 *
 * <p>{@code PlaceIngestController}와 같은 이유로 {@code @Profile("dev")}다: 운영에서는 엔드포인트 자체가 없고
 * {@link WeatherIngestScheduler}가 돌린다. 한 번 실행에 기상청 호출 6번(권역 4 + 중기 2)이라 부담은 작지만,
 * 누구나 반복 호출할 수 있는 운영 경로로 두지 않는다.
 */
@Profile("dev")
@Tag(name = "적재(개발용)", description = "공공 API에서 데이터를 받아 DB에 넣는다. 개발 프로필에서만 노출된다.")
@RestController
@RequestMapping("/admin/ingest")
public class WeatherIngestController {

    private final WeatherIngestService ingestService;

    public WeatherIngestController(WeatherIngestService ingestService) {
        this.ingestService = ingestService;
    }

    @Operation(summary = "기상청 날씨 예보 적재",
            description = "권역 4곳 단기(D+0~3)와 제주 전역 중기(D+4~7)를 weather_forecasts에 넣는다. "
                    + "같은 발표분은 지우고 다시 넣으므로 재실행 멱등. 운영은 스케줄러(03:30·06:30 KST)가 돌린다.")
    @PostMapping("/weather")
    public BaseResponse<WeatherIngestResult> ingestWeather() {
        return BaseResponse.success(ingestService.ingest());
    }
}
