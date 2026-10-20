package com.example.hangat.domain.alternative;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.common.geo.GeoService;
import com.example.hangat.common.model.BaseResponseStatus;
import com.example.hangat.domain.alternative.model.AlternativePlaceResponse;
import com.example.hangat.domain.congestion.CongestionService;
import com.example.hangat.map.model.entity.Place;
import com.example.hangat.map.model.enums.CongestionLevel;
import com.example.hangat.map.repository.PlaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 과밀 스팟 대안 제시 (담당: 정동현) - #과밀지역우회
 *
 * <p>규칙: 같은 카테고리 + 반경 내 + 그날 예보 존재 + 혼잡 미만, 집중률 낮은 순 상위 N.
 * [바운딩 박스 선필터(DB) → 하버사인 정밀 컷(자바)] 2단계 - GeoService 주석 참고.
 *
 * <p><b>반경은 10km 우선, 모자라면 20km까지 확장한다.</b> 화면이 "10km 후보를 먼저 표시하고,
 * 부족한 경우 20km 안의 조금 더 먼 대안을 함께 보여드려요"라고 안내하므로 응답도 후보마다
 * 어느 반경에서 나왔는지({@code radiusKm})를 알려준다. 가까운 대안이 있는데 먼 곳을 섞으면
 * "우회 때문에 하루를 버렸다"가 되므로 근거리를 항상 먼저 채운다.
 */
@Service
@Transactional(readOnly = true)
public class AlternativeService {
    public enum Sort { CONGESTION, DISTANCE }
    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record StraightResponse(LocalDate forecastDate, String distanceBasis, List<AlternativePlaceResponse> places) {}

    public StraightResponse straightAlternatives(Long placeId, Set<Long> excludeIds) {
        LocalDate today = com.example.hangat.common.util.DateTimes.todayKst();
        return new StraightResponse(today, "STRAIGHT_LINE",
                alternatives(placeId, today, excludeIds, Integer.MAX_VALUE, Sort.DISTANCE));
    }

    public record RoadCandidate(AlternativePlaceResponse place, com.example.hangat.course.route.AlternativeRoadDistance.Point point) {}
    public record RoadInput(LocalDate date, com.example.hangat.course.route.AlternativeRoadDistance.Point origin, List<RoadCandidate> candidates) {}

    /** Materialize DB fields before outbound requests; do not hold a DB transaction during routing. */
    public RoadInput roadInput(Long placeId) {
        LocalDate today = com.example.hangat.common.util.DateTimes.todayKst();
        Place origin = placeRepository.findById(placeId)
                .orElseThrow(() -> new BaseException(BaseResponseStatus.PLACE_NOT_FOUND, placeId));
        var responses = alternatives(placeId, today, Set.of(), Integer.MAX_VALUE, Sort.DISTANCE);
        var coordinates = placeRepository.findAllById(responses.stream().map(AlternativePlaceResponse::placeId).toList())
                .stream().collect(java.util.stream.Collectors.toMap(Place::getId, this::point));
        return new RoadInput(today, point(origin), responses.stream()
                .map(p -> new RoadCandidate(p, coordinates.get(p.placeId()))).toList());
    }

    private com.example.hangat.course.route.AlternativeRoadDistance.Point point(Place place) {
        if (place.getLatitude() == null || place.getLongitude() == null)
            throw new com.example.hangat.course.route.CourseCarRouteException("Missing place coordinates.");
        return new com.example.hangat.course.route.AlternativeRoadDistance.Point(place.getId(),
                place.getLatitude().doubleValue(), place.getLongitude().doubleValue());
    }

    /** 1차 탐색 반경(km) - 이 안에서 먼저 채운다. */
    private static final int NEAR_RADIUS_KM = 10;
    /** 2차 확장 반경(km) - 1차가 모자랄 때만 여기까지 본다. */
    private static final int FAR_RADIUS_KM = 20;

    private final PlaceRepository placeRepository;
    private final CongestionService congestionService;
    private final GeoService geoService;

    public AlternativeService(PlaceRepository placeRepository,
                              CongestionService congestionService,
                              GeoService geoService) {
        this.placeRepository = placeRepository;
        this.congestionService = congestionService;
        this.geoService = geoService;
    }

    public List<AlternativePlaceResponse> alternatives(Long placeId, LocalDate date,
                                                       Set<Long> excludeIds, int limit) {
        return alternatives(placeId, date, excludeIds, limit, Sort.CONGESTION);
    }

    public List<AlternativePlaceResponse> alternatives(Long placeId, LocalDate date,
                                                       Set<Long> excludeIds, int limit, Sort sort) {
        Place base = placeRepository.findById(placeId)
                .orElseThrow(() -> new BaseException(BaseResponseStatus.PLACE_NOT_FOUND, placeId));

        Map<Long, Double> rates = congestionService.ratesFor(date);
        if (rates.isEmpty()) {
            throw new BaseException(BaseResponseStatus.CONGESTION_NOT_FOUND, date.toString());
        }

        double baseRate = rates.getOrDefault(base.getId(), Double.NaN);

        // 넓은 쪽(20km) 한 번만 조회하고 자바에서 가까운 순으로 나눈다 - 쿼리를 두 번 치지 않는다
        GeoService.BoundingBox box = geoService.boxAround(
                base.getLatitude().doubleValue(), base.getLongitude().doubleValue(), FAR_RADIUS_KM);

        record Candidate(Place place, double rate, double distanceKm) {
        }

        List<Candidate> candidates = placeRepository.findCandidatesInBox(
                        base.getPrimaryCategory().getCode(),
                        BigDecimal.valueOf(box.minLat()), BigDecimal.valueOf(box.maxLat()),
                        BigDecimal.valueOf(box.minLng()), BigDecimal.valueOf(box.maxLng()))
                .stream()
                .filter(place -> !place.getId().equals(base.getId()))
                .filter(place -> !excludeIds.contains(place.getId()))
                .filter(place -> rates.containsKey(place.getId()))
                .filter(place -> CongestionLevel.from(BigDecimal.valueOf(rates.get(place.getId())))
                        != CongestionLevel.CROWDED)
                .map(place -> new Candidate(place, rates.get(place.getId()), geoService.distanceKm(
                        base.getLatitude(), base.getLongitude(),
                        place.getLatitude(), place.getLongitude())))
                .filter(c -> c.distanceKm() <= FAR_RADIUS_KM)
                .sorted((sort == Sort.DISTANCE
                        ? Comparator.comparingDouble(Candidate::distanceKm)
                        : Comparator.comparingDouble(Candidate::rate))
                        .thenComparing(c -> c.place().getId()))
                .toList();

        // 10km 안을 먼저 채우고, 모자란 자리만 20km에서 보충한다
        List<AlternativePlaceResponse> results = new ArrayList<>();
        for (int radius : new int[]{NEAR_RADIUS_KM, FAR_RADIUS_KM}) {
            for (Candidate c : candidates) {
                if (results.size() >= limit) {
                    break;
                }
                boolean inThisRing = radius == NEAR_RADIUS_KM
                        ? c.distanceKm() <= NEAR_RADIUS_KM
                        : c.distanceKm() > NEAR_RADIUS_KM;
                if (inThisRing) {
                    results.add(toResponse(base, baseRate, c.place(), c.rate(), c.distanceKm(), radius));
                }
            }
        }
        return results;
    }

    private AlternativePlaceResponse toResponse(Place base, double baseRate, Place candidate,
                                                double rate, double distanceKm, int radiusKm) {
        CongestionLevel level = CongestionLevel.from(BigDecimal.valueOf(rate));
        return AlternativePlaceResponse.of(candidate, rate, level, distanceKm, radiusKm,
                recommendationReason(candidate, level),
                replacementReason(base, baseRate, rate, distanceKm));
    }

    /** 이 장소가 어떤 곳인지 - MainService와 같은 우선순위 (검증가 > 숨은 명소 > 예보 한산) */
    private String recommendationReason(Place place, CongestionLevel level) {
        if (place.isGoodPrice()) return "착한가격업소 검증가";
        if (place.isHiddenGem()) return "덜 알려진 숨은 명소";
        if (level == CongestionLevel.QUIET) return "이 날짜 혼잡 예보가 한산이에요";
        return "인기 명소보다 한산한 편이에요";
    }

    /**
     * 왜 바꾸면 나은지 - 교체 버튼 옆 한 줄.
     * 기준 장소의 그날 예보가 없으면(커버 밖) 비교 문구를 만들지 않는다 - 없는 수치를 지어내지 않는다.
     */
    private String replacementReason(Place base, double baseRate, double rate, double distanceKm) {
        String distanceText = distanceKm < 1
                ? Math.round(distanceKm * 1000) + "m"
                : String.format("%.1fkm", distanceKm);
        if (Double.isNaN(baseRate)) {
            return base.getName() + "에서 " + distanceText + " 거리예요";
        }
        int gap = (int) Math.round(baseRate - rate);
        if (gap <= 0) {
            return base.getName() + "에서 " + distanceText + ", 혼잡도는 비슷해요";
        }
        return base.getName() + "보다 집중률이 " + gap + " 낮고 " + distanceText + " 거리예요";
    }
}
