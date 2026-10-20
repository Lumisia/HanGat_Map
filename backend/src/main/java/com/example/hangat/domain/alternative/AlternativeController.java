package com.example.hangat.domain.alternative;

import com.example.hangat.common.model.BaseResponse;
import com.example.hangat.domain.alternative.model.AlternativePlaceResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/places")
@Validated
public class AlternativeController {

    private final AlternativeService alternativeService;
    private final RoadAlternativeService roadAlternativeService;

    public AlternativeController(AlternativeService alternativeService, RoadAlternativeService roadAlternativeService) {
        this.alternativeService = alternativeService;
        this.roadAlternativeService = roadAlternativeService;
    }

    @GetMapping("/{placeId}/straight-alternatives")
    @Operation(summary = "오늘 예보 기준 직선거리 20km 이내 대안", description = "같은 카테고리·혼잡 미만 후보를 직선거리순으로 반환합니다. 전체 결과를 화면에서 3개씩 표시하며 자동차 경로 API는 호출하지 않습니다.")
    public BaseResponse<AlternativeService.StraightResponse> straightAlternatives(@PathVariable Long placeId,
            @RequestParam(required = false, defaultValue = "") Set<Long> exclude) {
        return BaseResponse.success(alternativeService.straightAlternatives(placeId, exclude));
    }

    @GetMapping("/{placeId}/road-alternatives")
    @Operation(summary = "오늘 예보 기준 자동차 도로거리 20km 이내 대안", description = "같은 카테고리·혼잡 미만 후보를 최단 자동차 도로거리순으로 반환합니다. 전체 결과를 화면에서 3개씩 표시하며, 예보·경로 조회 실패는 빈 목록과 구분합니다.")
    public BaseResponse<RoadAlternativeService.Response> roadAlternatives(@PathVariable Long placeId,
            @RequestParam(required = false, defaultValue = "") Set<Long> exclude) {
        return BaseResponse.success(roadAlternativeService.alternatives(placeId,exclude));
    }

    @GetMapping("/{placeId}/alternatives")
    @Operation(summary = "과밀 스팟 대안 장소", description = """
            같은 카테고리·해당 날짜(기본: 오늘) 예보가 혼잡 미만인 장소를 집중률 낮은 순으로 반환한다.
            반경은 10km를 먼저 채우고 모자랄 때만 20km까지 넓히며, 후보마다 radius_km로 알려준다.
            exclude로 이미 코스에 포함된 장소를 제외할 수 있다.
            sort=DISTANCE는 거리순이며, 기본 CONGESTION은 기존 집중률순이다.
            응답은 코스 도메인과 같은 snake_case 계약이다.
            혼잡 예보는 날짜 단위로만 제공된다 (시간대 예측 없음).""")
    public BaseResponse<List<AlternativePlaceResponse>> alternatives(
            @PathVariable Long placeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false, defaultValue = "") Set<Long> exclude,
            @RequestParam(defaultValue = "3") @Min(1) @Max(10) int limit,
            @RequestParam(defaultValue = "CONGESTION") AlternativeService.Sort sort) {
        LocalDate target = date != null ? date : com.example.hangat.common.util.DateTimes.todayKst();
        return BaseResponse.success(alternativeService.alternatives(placeId, target, exclude, limit, sort));
    }
}
