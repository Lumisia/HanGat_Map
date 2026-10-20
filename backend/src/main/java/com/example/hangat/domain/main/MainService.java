package com.example.hangat.domain.main;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.common.model.BaseResponseStatus;
import com.example.hangat.domain.congestion.CongestionService;
import com.example.hangat.map.model.enums.CongestionLevel;
import com.example.hangat.domain.main.model.CalmPlaceResponse;
import com.example.hangat.map.model.entity.Place;
import com.example.hangat.map.repository.PlaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 메인 페이지 추천 (담당: 정동현) - 오늘의 한산 장소 (MAIN_001)
 *
 * <p><b>2026-08-24 통합</b>: 장소·혼잡 데이터를 {@code map} 도메인 것으로 바꿨다.
 * 시드 50곳 대신 KTO 실적재 2,138곳을 보게 되어 추천 후보가 넓어진다.
 * 화면 계약({@link CalmPlaceResponse})은 그대로라 프론트는 영향이 없다.
 */
@Service
@Transactional(readOnly = true)
public class MainService {

    /** 관광지 카테고리 코드 - place_categories.code (명세서 6.0). enum이 아니라 마스터 테이블 값이다. */
    private static final String TOURIST_CODE = "TOURIST";

    private final PlaceRepository placeRepository;
    private final CongestionService congestionService;

    public MainService(PlaceRepository placeRepository, CongestionService congestionService) {
        this.placeRepository = placeRepository;
        this.congestionService = congestionService;
    }

    /**
     * 해당 날짜 예보 기준 집중률 낮은 순 관광지 상위 N.
     * 혼잡(CROWDED) 이상은 하드 컷 - 추천 목록에 아예 올리지 않는다.
     */
    public List<CalmPlaceResponse> calmPlaces(LocalDate date, int limit) {
        Map<Long, Double> rates = congestionService.ratesFor(date);
        if (rates.isEmpty()) {
            throw new BaseException(BaseResponseStatus.CONGESTION_NOT_FOUND, date.toString());
        }

        // 연관(region·category)은 지연로딩이지만 application.yaml의 default_batch_fetch_size=100이
        // IN 절로 묶어 준다 - 후보가 수백 건이라 별도 fetch join 없이 감당된다
        return placeRepository.findAllById(rates.keySet()).stream()
                .filter(place -> TOURIST_CODE.equals(place.getPrimaryCategory().getCode()))
                .map(place -> toResponse(place, rates.get(place.getId())))
                .filter(response -> response.level() == CongestionLevel.QUIET
                        || response.level() == CongestionLevel.NORMAL)
                .sorted(Comparator.comparingDouble(CalmPlaceResponse::rate))
                .limit(limit)
                .toList();
    }

    private CalmPlaceResponse toResponse(Place place, double rate) {
        CongestionLevel level = congestionService.levelOf(rate);
        return CalmPlaceResponse.of(place, rate, level, reasonFor(place, level));
    }

    /** 추천 근거 칩 - 우선순위: 검증가 > 숨은 명소 > 예보 한산 (프론트 문구와 동일 규칙) */
    private String reasonFor(Place place, CongestionLevel level) {
        if (place.isGoodPrice()) return "착한가격업소 검증가";
        if (place.isHiddenGem()) return "덜 알려진 숨은 명소";
        if (level == CongestionLevel.QUIET) return "이 날짜 혼잡 예보가 한산이에요";
        return "인기 명소보다 한산한 편이에요";
    }
}
