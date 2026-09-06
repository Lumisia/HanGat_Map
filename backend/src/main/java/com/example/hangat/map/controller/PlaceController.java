package com.example.hangat.map.controller;

import com.example.hangat.common.model.BaseResponse;
import com.example.hangat.map.model.dto.PlaceDetailResponse;
import com.example.hangat.map.model.dto.PlaceListResponse;
import com.example.hangat.map.service.PlaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 장소 조회 API - 설계서 §2.1. 지도 화면(MAP_001~009)과 관광지 상세의 단일 데이터 소스(§1.1).
 * 실패는 BaseException을 던지고 GlobalExceptionHandler가 상태코드를 매긴다(3000번대 → 400).
 */
@Tag(name = "장소", description = "지도 마커·목록·상세")
@RestController
@RequestMapping("/places")
public class PlaceController {

    private final PlaceService placeService;

    public PlaceController(PlaceService placeService) {
        this.placeService = placeService;
    }

    /** type을 enum이 아닌 String으로 받는다 - Spring 기본 변환기는 소문자 {@code spot}을 못 읽고 그 실패가 BaseResponse 봉투를 벗어난다. */
    @Operation(summary = "장소 목록",
            description = "제주 전역을 한 번에 반환한다(수백 건 규모, 페이징 없음). 폐업(CLOSED)은 제외. 모르는 type은 3000(400).")
    @GetMapping
    public BaseResponse<List<PlaceListResponse>> getPlaces(
            @Parameter(description = "spot/food/dine/cafe/cvs/stay/mart. 생략하거나 빈 값이면 전체")
            @RequestParam(name = "type", required = false) String type) {
        return BaseResponse.success(placeService.getPlaces(type));
    }

    @Operation(summary = "장소 통합 검색",
            description = "이름·대표 메뉴(overview) 부분 일치 상위 20건. 2글자 미만은 빈 목록. 좌표 없는 장소는 제외. "
                    + "region·categories 를 주면 화면 필터(권역·업종 칩) 범위 안에서만 찾는다.")
    @GetMapping("/search")
    public BaseResponse<List<PlaceListResponse>> searchPlaces(
            @Parameter(description = "검색어 (2글자 이상)")
            @RequestParam(name = "q") String q,
            @Parameter(description = "권역 코드 (EAST/WEST/SOUTH/NORTH). 생략하면 전체")
            @RequestParam(name = "region", required = false) String region,
            @Parameter(description = "카테고리 코드 목록 (쉼표 구분, 예: TOURIST,FOOD). 생략하면 전체")
            @RequestParam(name = "categories", required = false) List<String> categories) {
        return BaseResponse.success(placeService.searchPlaces(q, region, categories));
    }

    @Operation(summary = "장소 상세", description = "없는 id면 PLACE_NOT_FOUND(3201) → HTTP 400. 폐업 장소도 반환한다(찜·공유 링크 보호).")
    @GetMapping("/{placeId}")
    public BaseResponse<PlaceDetailResponse> getPlace(@PathVariable("placeId") Long placeId) {
        return BaseResponse.success(placeService.getPlace(placeId));
    }
}
