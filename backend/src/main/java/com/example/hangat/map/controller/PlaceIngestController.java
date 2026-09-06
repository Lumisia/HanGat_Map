package com.example.hangat.map.controller;

import com.example.hangat.common.model.BaseResponse;
import com.example.hangat.map.service.CongestionIngestService;
import com.example.hangat.map.detail.MenuIngestService;
import com.example.hangat.map.detail.PlaceDetailIngestService;
import com.example.hangat.map.image.PlaceImageIngestService;
import com.example.hangat.map.service.PlaceIngestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 적재 배치 수동 실행 - <b>개발 전용 도구</b>
 *
 * <p>{@code @Profile("dev")}를 붙인 이유: 이 엔드포인트는 공공 API를 수천 번 호출한다.
 * 현재 {@code SecurityConfig}가 전부 {@code permitAll}이라 운영에 그대로 배포되면
 * 누구나 반복 호출해 하루 1,000건 쿼터를 태울 수 있다.
 * 개발 프로필에서만 빈이 만들어지므로 운영에는 <b>엔드포인트 자체가 존재하지 않는다</b>.
 *
 * <p>인증·권한 체계가 붙으면(회원 담당) 관리자 권한 검사로 바꾸고 프로필 제한을 걷어낸다.
 * 자동 스케줄(@Scheduled)은 적재 동작이 검증된 뒤에 붙인다.
 */
@Profile("dev")
@Tag(name = "적재(개발용)", description = "공공 API에서 데이터를 받아 DB에 넣는다. 개발 프로필에서만 노출된다.")
@RestController
@RequestMapping("/admin/ingest")
public class PlaceIngestController {

    private final PlaceIngestService placeIngestService;
    private final CongestionIngestService congestionIngestService;
    private final PlaceDetailIngestService placeDetailIngestService;
    private final PlaceImageIngestService placeImageIngestService;
    private final com.example.hangat.map.goodprice.GoodPriceIngestService goodPriceIngestService;
    private final com.example.hangat.map.store.StoreIngestService storeIngestService;
    private final MenuIngestService menuIngestService;

    public PlaceIngestController(PlaceIngestService placeIngestService,
                                 CongestionIngestService congestionIngestService,
                                 PlaceDetailIngestService placeDetailIngestService,
                                 PlaceImageIngestService placeImageIngestService,
                                 com.example.hangat.map.goodprice.GoodPriceIngestService goodPriceIngestService,
                                 com.example.hangat.map.store.StoreIngestService storeIngestService,
                                 MenuIngestService menuIngestService) {
        this.placeIngestService = placeIngestService;
        this.congestionIngestService = congestionIngestService;
        this.placeDetailIngestService = placeDetailIngestService;
        this.placeImageIngestService = placeImageIngestService;
        this.goodPriceIngestService = goodPriceIngestService;
        this.storeIngestService = storeIngestService;
        this.menuIngestService = menuIngestService;
    }

    @Operation(summary = "카페·편의점·마트 적재 (MAP-04)",
            description = "소상공인 상가정보를 업종 3종(카페·편의점·슈퍼마켓)으로 수집한다. 좌표 포함이라 지오코딩 불필요 - 재실행 멱등.")
    @PostMapping("/stores")
    public BaseResponse<com.example.hangat.map.store.StoreIngestService.StoreIngestResult> ingestStores() {
        return BaseResponse.success(storeIngestService.ingest());
    }

    @Operation(summary = "착한가격업소 적재 (MAP-04)",
            description = "행안부 CSV(리소스 포함)의 외식업을 적재한다. 좌표는 카카오 로컬로 지오코딩 - 재실행 멱등.")
    @PostMapping("/goodprice")
    public BaseResponse<com.example.hangat.map.goodprice.GoodPriceIngestService.GoodPriceResult> ingestGoodPrice() {
        return BaseResponse.success(goodPriceIngestService.ingest());
    }

    @Operation(summary = "장소 상세 정보 적재 (MAP-07)",
            description = "운영시간·쉬는날·주차·입장료를 채운다. 장소당 1콜이라 일일 쿼터를 넘으므로 "
                    + "상세가 비어 있는 곳부터 limit만큼 처리하고, remaining이 0이 될 때까지 다시 실행하면 이어진다.")
    @PostMapping("/details")
    public BaseResponse<PlaceDetailIngestService.DetailIngestResult> ingestDetails(
            @RequestParam(name = "limit", required = false,
                    defaultValue = "" + PlaceDetailIngestService.DEFAULT_LIMIT) int limit) {
        return BaseResponse.success(placeDetailIngestService.ingest(limit));
    }

    @Operation(summary = "장소 사진 적재 (MAP-08)",
            description = "KTO detailImage2 사진을 place_images 에 채운다. 장소당 1콜 - "
                    + "사진 없는 곳부터 limit만큼 처리하고 remaining이 0이 될 때까지 다시 실행하면 이어진다.")
    @PostMapping("/images")
    public BaseResponse<PlaceImageIngestService.ImageIngestResult> ingestImages(
            @RequestParam(name = "limit", required = false,
                    defaultValue = "" + PlaceImageIngestService.DEFAULT_LIMIT) int limit) {
        return BaseResponse.success(placeImageIngestService.ingest(limit));
    }

    @Operation(summary = "음식점 대표 메뉴 적재 (MAP-07)",
            description = "KTO detailIntro2의 firstmenu·treatmenu를 overview에 채운다(가격 미제공). "
                    + "메뉴 없는 음식점부터 limit만큼 처리하고 remaining이 0에 가까워질 때까지 다시 실행하면 이어진다.")
    @PostMapping("/menus")
    public BaseResponse<MenuIngestService.MenuIngestResult> ingestMenus(
            @RequestParam(name = "limit", required = false,
                    defaultValue = "" + MenuIngestService.DEFAULT_LIMIT) int limit) {
        return BaseResponse.success(menuIngestService.ingest(limit));
    }

    @Operation(summary = "KTO 관광정보 적재",
            description = "제주 전역 관광정보를 받아 places에 넣는다. 이미 있는 장소는 변경분만 갱신한다. "
                    + "실측 기준 약 2,147건 수신 / 11건은 권역 판정 불가로 제외(추자도 등).")
    @PostMapping("/places")
    public BaseResponse<PlaceIngestService.IngestResult> ingestPlaces() {
        return BaseResponse.success(placeIngestService.ingest());
    }

    @Operation(summary = "관광지별 집중률 적재",
            description = "제주 전역 집중률을 받아 congestion_forecasts에 넣는다. "
                    + "장소 매칭은 이름으로만 가능해 일부는 붙지 않는다(실측 448곳 중 347곳). "
                    + "같은 날 다시 돌리면 그날 발표 버전만 지우고 다시 넣는다.")
    @PostMapping("/congestion")
    public BaseResponse<CongestionIngestService.CongestionIngestResult> ingestCongestion() {
        return BaseResponse.success(congestionIngestService.ingest());
    }
}
