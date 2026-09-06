package com.example.hangat.course.route;

import com.example.hangat.map.goodprice.KakaoLocalClient;
import com.example.hangat.map.goodprice.KakaoLocalClient.KakaoPlace;
import com.example.hangat.map.repository.PlaceRepository;
import com.example.hangat.map.service.RegionResolver;
import org.springframework.stereotype.Component;
import java.util.Comparator;
import java.util.Optional;

/** Fail-closed route-only projection. No entity mutations or provider mapping writes. */
@Component
public class RouteAccessPointResolver {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RouteAccessPointResolver.class);
    // Conservative policy, not an API guarantee. No expanding-radius fallback.
    static final int MAX_DISTANCE_METERS = 1000;
    private final KakaoLocalClient kakao;
    private final PlaceRepository places;
    private final RegionResolver regions;
    private final com.example.hangat.map.repository.PlaceSourceMappingRepository mappings;
    @org.springframework.beans.factory.annotation.Autowired
    public RouteAccessPointResolver(KakaoLocalClient kakao, PlaceRepository places, RegionResolver regions,
            com.example.hangat.map.repository.PlaceSourceMappingRepository mappings) {
        this.kakao = kakao; this.places = places; this.regions = regions; this.mappings = mappings;
    }
    public RouteAccessPointResolver(KakaoLocalClient kakao, PlaceRepository places, RegionResolver regions) {
        this(kakao, places, regions, null);
    }

    public Optional<CarRouteResponse.AccessPoint> resolve(Long placeId) {
        var original = places.findDetailById(placeId).orElse(null);
        if (original == null || original.getRegion() == null || original.getName() == null
                || original.getName().isBlank() || original.getLatitude() == null
                || original.getLongitude() == null) return Optional.empty();
        double lat = original.getLatitude().doubleValue(), lon = original.getLongitude().doubleValue();
        if (!valid(lat, lon)) return Optional.empty();
        var trusted = mappings == null ? null : mappings.findByPlaceIdAndSourceCode(placeId, "KTO")
                .filter(m -> m.isActive()).flatMap(m -> TrustedRouteAccessRegistry.find("KTO", m.getSourcePlaceId())).orElse(null);
        boolean mountain = trusted != null || (original.getPrimaryCategory() != null
                && "TOURIST".equals(original.getPrimaryCategory().getCode())
                && TrustedRouteAccessRegistry.normalizedName(original.getName()).matches(".+(산|악|오름|탐방로)$"));
        java.util.List<String> queries = trusted != null ? java.util.List.of(trusted.accessName())
                : new java.util.ArrayList<>(TrustedRouteAccessRegistry.mountainQueries(original.getName(), mountain));
        if (trusted == null) queries.add(null); // final bounded PK6 query
        // No verified entrance coordinate exists in the current Place/mapping contract.
        // Trusted identity: one search. Otherwise at most five bounded searches; one region lookup.
        for (String query : queries) {
            log.info("ROUTE_ACCESS_LOCAL_SEARCH kind={}", trusted != null ? "TRUSTED" : query == null ? "PK6" : "KEYWORD");
            var candidates = kakao.searchRouteAccessPoints(query,
                    original.getLongitude(), original.getLatitude(), trusted == null ? MAX_DISTANCE_METERS : trusted.maxDistanceMeters());
            var candidate = candidates.stream().filter(p -> trusted == null ? associated(p, original.getName(), lat, lon)
                    : trusted.matches(p) && p.latitude() != null && p.longitude() != null
                    && valid(p.latitude().doubleValue(), p.longitude().doubleValue())
                    && distance(lat, lon, p.latitude().doubleValue(), p.longitude().doubleValue()) > 0
                    && distance(lat, lon, p.latitude().doubleValue(), p.longitude().doubleValue()) <= trusted.maxDistanceMeters())
                    .min(Comparator.comparingDouble((KakaoPlace p) -> distance(lat, lon,
                            p.latitude().doubleValue(), p.longitude().doubleValue())).thenComparing(KakaoPlace::id))
                    .orElse(null);
            if (candidate == null) continue;
            log.info("ROUTE_ACCESS_REGION_LOOKUP");
            var official = kakao.resolveAdministrativeRegion(candidate.longitude(), candidate.latitude()).orElse(null);
            if (official == null) return Optional.empty();
            String region = regions.resolveAdministrativeRegion(official.region1DepthName(),
                    official.region2DepthName(), official.region3DepthName());
            if (!original.getRegion().getCode().equals(region)) return Optional.empty();
            boolean parking = "PK6".equals(candidate.categoryGroupCode());
            return Optional.of(new CarRouteResponse.AccessPoint(original.getId(), original.getName(),
                    parking ? "PARKING" : "PLACE_ENTRANCE", candidate.name(),
                    candidate.latitude().doubleValue(), candidate.longitude().doubleValue(),
                    Math.round(distance(lat, lon, candidate.latitude().doubleValue(), candidate.longitude().doubleValue())),
                    "KAKAO_LOCAL", candidate.id(), (trusted == null ? original.getName() : trusted.canonicalName()) + " 인근 " + candidate.name()
                    + "까지 차량 경로 · 관광지 대표 좌표 대신 확인된 차량 진입점을 사용했어요. 관광지까지는 직선거리 참고값입니다."));
        }
        return Optional.empty();
    }

    static boolean associated(KakaoPlace p, String originalName, double lat, double lon) {
        if (p == null || p.id() == null || !p.id().matches("[0-9]+") || p.name() == null
                || p.latitude() == null || p.longitude() == null) return false;
        double y = p.latitude().doubleValue(), x = p.longitude().doubleValue();
        String name = TrustedRouteAccessRegistry.normalizedName(p.name()), original = TrustedRouteAccessRegistry.normalizedName(originalName);
        return !original.isEmpty() && valid(y, x) && distance(lat, lon, y, x) > 0
                && distance(lat, lon, y, x) <= MAX_DISTANCE_METERS
                && name.contains(original) && ("PK6".equals(p.categoryGroupCode()) || name.endsWith("입구"));
    }
    private static boolean valid(double lat, double lon) {
        return Double.isFinite(lat) && Double.isFinite(lon) && lat >= 32.9 && lat <= 33.7 && lon >= 126 && lon <= 127.1;
    }
    static double distance(double lat, double lon, double y, double x) {
        double a = Math.pow(Math.sin(Math.toRadians(y-lat)/2),2)
                + Math.cos(Math.toRadians(lat))*Math.cos(Math.toRadians(y))*Math.pow(Math.sin(Math.toRadians(x-lon)/2),2);
        return 6371000 * 2 * Math.asin(Math.sqrt(Math.min(1,a)));
    }
}
