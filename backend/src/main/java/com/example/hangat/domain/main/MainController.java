package com.example.hangat.domain.main;

import com.example.hangat.common.model.BaseResponse;
import com.example.hangat.domain.main.model.CalmPlaceResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/main")
@Validated
public class MainController {

    private final MainService mainService;

    public MainController(MainService mainService) {
        this.mainService = mainService;
    }

    @GetMapping("/calm-places")
    @Operation(summary = "오늘의 한산 장소", description = """
            해당 날짜(기본: 오늘) 혼잡 예보 기준 집중률 낮은 순 관광지 상위 N.
            혼잡 등급 이상은 제외한다. 예보는 날짜 단위로만 제공된다 (시간대 예측 없음).""")
    public BaseResponse<List<CalmPlaceResponse>> calmPlaces(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "7") @Min(1) @Max(20) int limit) {
        LocalDate target = date != null ? date : com.example.hangat.common.util.DateTimes.todayKst();
        return BaseResponse.success(mainService.calmPlaces(target, limit));
    }
}
