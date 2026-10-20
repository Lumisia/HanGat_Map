package com.example.hangat.map.controller;

import com.example.hangat.common.model.BaseResponse;
import com.example.hangat.map.model.dto.CrowdForecastResponse;
import com.example.hangat.map.service.CrowdForecastService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 혼잡 예보 API - 설계서 §2.2. 지도 핀 색(MAP-01)과 날짜 슬라이더(MAP-02)가 이 하나를 쓴다.
 */
@Tag(name = "혼잡", description = "관광지별 집중률 예보")
@RestController
@RequestMapping("/crowd")
public class CrowdController {

    private final CrowdForecastService crowdForecastService;

    public CrowdController(CrowdForecastService crowdForecastService) {
        this.crowdForecastService = crowdForecastService;
    }

    @Operation(summary = "혼잡 예보 전체",
            description = "최신 발표 버전의 장소별 날짜순 집중률을 한 번에 반환한다. "
                    + "날짜를 바꿀 때마다 다시 부르지 않아도 되도록 통째로 준다(MAP-02는 0.3초 이내 갱신 요건). "
                    + "예보 일수는 날마다 달라 days로 함께 내려준다. 예보 없는 장소는 values에 없다. "
                    + "baseDate는 발표분을 받아 온 날이다. 새벽 배치 시각에는 관광공사 API가 어제부터 시작하는 창을 주므로 "
                    + "from은 정상 적재에서도 어제다 - 예보가 묵었는지는 baseDate로 판단한다.")
    @GetMapping("/forecast")
    public BaseResponse<CrowdForecastResponse> getForecast() {
        return BaseResponse.success(crowdForecastService.getForecast());
    }
}
