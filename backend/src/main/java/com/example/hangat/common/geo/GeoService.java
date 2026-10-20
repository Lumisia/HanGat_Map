package com.example.hangat.common.geo;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class GeoService {

    private static final double EARTH_RADIUS_KM = 6371.0;

    /** 두 지점 사이 직선거리(km) - 하버사인 공식 */
    public double distanceKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double h = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.pow(Math.sin(dLng / 2), 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(h));
    }

    /** 엔티티 좌표(BigDecimal)용 편의 오버로드 */
    public double distanceKm(BigDecimal lat1, BigDecimal lng1, BigDecimal lat2, BigDecimal lng2) {
        return distanceKm(lat1.doubleValue(), lng1.doubleValue(),
                lat2.doubleValue(), lng2.doubleValue());
    }

    /** 중심점 기준 반경을 덮는 바운딩 박스 - DB 선필터(WHERE lat BETWEEN ...)용 */
    public BoundingBox boxAround(double lat, double lng, double radiusKm) {
        // Use the same sphere as distanceKm so the DB prefilter never clips the radius edge.
        double angularRadius = radiusKm / EARTH_RADIUS_KM;
        double latDelta = Math.toDegrees(angularRadius);
        double lngDelta = Math.abs(lat) + latDelta >= 90 ? 180
                : Math.toDegrees(Math.asin(Math.min(1, Math.sin(angularRadius) / Math.cos(Math.toRadians(lat)))));
        return new BoundingBox(lat - latDelta, lat + latDelta, lng - lngDelta, lng + lngDelta);
    }

    public record BoundingBox(double minLat, double maxLat, double minLng, double maxLng) {
    }

}
