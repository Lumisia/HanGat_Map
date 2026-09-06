package com.example.hangat.map.goodprice;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 카카오 로컬 주소 검색 - 주소 문자열을 좌표로 바꾼다 (지오코딩).
 * 착한가격 CSV 에는 좌표가 없어서(주소만) 이 변환이 필요하다.
 * 키는 지도 앱('한갓지도')의 REST 키 - 로그인용 KAKAO_CLIENT_ID 와 다른 앱이다.
 */
@Component
public class KakaoLocalClient {

    private static final Logger log = LoggerFactory.getLogger(KakaoLocalClient.class);
    private static final String ADDRESS_PATH = "/v2/local/search/address.json";
    private static final String CATEGORY_PATH = "/v2/local/search/category.json";
    private static final String REGION_PATH = "/v2/local/geo/coord2regioncode.json";
    private static final String LODGING_CATEGORY = "AD5";

    private final RestClient restClient;
    private final String restKey;

    public KakaoLocalClient(
            RestClient.Builder builder,
            @Value("${kakao-local.rest-key:}") String restKey,
            @Value("${kakao-local.base-url:https://dapi.kakao.com}") String baseUrl
    ) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.restKey = restKey;
    }

    public record GeoPoint(BigDecimal latitude, BigDecimal longitude) {
    }

    public record KakaoPlace(
            String id, String name, String address, String roadAddress,
            BigDecimal latitude, BigDecimal longitude,
            String categoryGroupCode, String categoryName,
            String phone, String placeUrl, Integer distanceMeters
    ) {
    }

    public record KakaoAdministrativeRegion(
            String region1DepthName, String region2DepthName,
            String region3DepthName, String code
    ) {
    }

    /** 주소 → 좌표. 못 찾으면 empty - 그 업소는 지도에 못 찍으므로 호출부에서 뺀다 */
    public Optional<GeoPoint> geocode(String address) {
        if (restKey.isBlank()) {
            throw new IllegalStateException("KAKAO_LOCAL_REST_KEY 가 없습니다 - .env 확인");
        }
        try {
            JsonNode body = restClient.get()
                    .uri(UriComponentsBuilder.fromPath(ADDRESS_PATH)
                            .queryParam("query", address)
                            .build().toUriString())
                    .header("Authorization", "KakaoAK " + restKey)
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode docs = body == null ? null : body.path("documents");
            if (docs == null || !docs.isArray() || docs.isEmpty()) {
                return Optional.empty();
            }
            JsonNode first = docs.get(0);
            return Optional.of(new GeoPoint(
                    new BigDecimal(first.path("y").asText()),
                    new BigDecimal(first.path("x").asText())));
        } catch (Exception e) {
            log.debug("지오코딩 실패: {} ({})", address, e.getMessage());
            return Optional.empty();
        }
    }

    /** Course 좌표 주변의 Kakao 공식 숙박(AD5) 결과만 조회한다. */
    public List<KakaoPlace> searchLodgings(
            BigDecimal longitude, BigDecimal latitude, int radiusMeters
    ) {
        requireKey();
        JsonNode body = restClient.get()
                .uri(UriComponentsBuilder.fromPath(CATEGORY_PATH)
                        .queryParam("category_group_code", LODGING_CATEGORY)
                        .queryParam("x", longitude.toPlainString())
                        .queryParam("y", latitude.toPlainString())
                        .queryParam("radius", radiusMeters)
                        .queryParam("size", 15)
                        .queryParam("sort", "distance")
                        .build().toUriString())
                .header("Authorization", "KakaoAK " + restKey)
                .retrieve()
                .body(JsonNode.class);
        return parsePlaces(body);
    }

    /** One bounded page of route-only parking/entrance candidates; never persisted. */
    public List<KakaoPlace> searchRouteAccessPoints(String query, BigDecimal longitude,
            BigDecimal latitude, int radiusMeters) {
        requireKey();
        var uri = UriComponentsBuilder.fromPath(query == null ? CATEGORY_PATH : "/v2/local/search/keyword.json")
                .queryParam("x", longitude.toPlainString()).queryParam("y", latitude.toPlainString())
                .queryParam("radius", radiusMeters).queryParam("size", 15).queryParam("sort", "distance");
        if (query == null) uri.queryParam("category_group_code", "PK6");
        else uri.queryParam("query", query);
        return parsePlaces(restClient.get().uri(uri.build().toUriString())
                .header("Authorization", "KakaoAK " + restKey).retrieve().body(JsonNode.class));
    }

    private List<KakaoPlace> parsePlaces(JsonNode body) {
        JsonNode docs = documents(body);
        if (docs == null) {
            return List.of();
        }
        List<KakaoPlace> results = new ArrayList<>();
        for (JsonNode doc : docs) {
            try {
                results.add(new KakaoPlace(
                        requiredText(doc, "id"), requiredText(doc, "place_name"),
                        text(doc, "address_name"), text(doc, "road_address_name"),
                        decimal(doc, "y"), decimal(doc, "x"),
                        text(doc, "category_group_code"), text(doc, "category_name"),
                        text(doc, "phone"), text(doc, "place_url"), integer(doc, "distance")));
            } catch (RuntimeException ignored) {
                // identity/좌표가 없는 문서는 저장 후보가 될 수 없다.
            }
        }
        return List.copyOf(results);
    }

    /** 좌표를 Kakao 공식 행정구역(H)으로 변환한다. */
    public Optional<KakaoAdministrativeRegion> resolveAdministrativeRegion(
            BigDecimal longitude, BigDecimal latitude
    ) {
        requireKey();
        JsonNode body = restClient.get()
                .uri(UriComponentsBuilder.fromPath(REGION_PATH)
                        .queryParam("x", longitude.toPlainString())
                        .queryParam("y", latitude.toPlainString())
                        .queryParam("input_coord", "WGS84")
                        .build().toUriString())
                .header("Authorization", "KakaoAK " + restKey)
                .retrieve()
                .body(JsonNode.class);
        JsonNode docs = documents(body);
        if (docs == null) {
            return Optional.empty();
        }
        for (JsonNode doc : docs) {
            if ("H".equals(text(doc, "region_type"))) {
                return Optional.of(new KakaoAdministrativeRegion(
                        text(doc, "region_1depth_name"), text(doc, "region_2depth_name"),
                        text(doc, "region_3depth_name"), text(doc, "code")));
            }
        }
        return Optional.empty();
    }

    private JsonNode documents(JsonNode body) {
        JsonNode docs = body == null ? null : body.path("documents");
        return docs != null && docs.isArray() ? docs : null;
    }

    private void requireKey() {
        if (restKey == null || restKey.isBlank()) {
            throw new IllegalStateException("KAKAO_LOCAL_REST_KEY가 설정되지 않았습니다.");
        }
    }

    private String requiredText(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) throw new IllegalArgumentException(field + "가 없습니다.");
        return value;
    }

    private String text(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        return value.isEmpty() ? null : value;
    }

    private BigDecimal decimal(JsonNode node, String field) {
        return new BigDecimal(requiredText(node, field));
    }

    private Integer integer(JsonNode node, String field) {
        String value = text(node, field);
        return value == null ? null : Integer.valueOf(value);
    }
}
