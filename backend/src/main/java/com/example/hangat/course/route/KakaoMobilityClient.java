package com.example.hangat.course.route;

import com.example.hangat.course.route.CarRouteResponse.Coordinate;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
public class KakaoMobilityClient {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(KakaoMobilityClient.class);
    static final String DIRECTIONS_PATH = "/v1/directions";
    private final RestClient restClient;
    private final String restKey;
    private final int maxAttempts;
    private final long backoffMillis;

    @Autowired
    public KakaoMobilityClient(RestClient.Builder builder,
            @Value("${kakao-mobility.rest-key:${kakao-local.rest-key:}}") String restKey,
            @Value("${kakao-mobility.base-url:https://apis-navi.kakaomobility.com}") String baseUrl,
            @Value("${kakao-mobility.connect-timeout:3s}") Duration connectTimeout,
            @Value("${kakao-mobility.read-timeout:15s}") Duration readTimeout,
            @Value("${kakao-mobility.max-attempts:3}") int maxAttempts,
            @Value("${kakao-mobility.retry-backoff:200ms}") Duration retryBackoff) {
        this(createRestClient(builder, baseUrl, connectTimeout, readTimeout), restKey,
                maxAttempts, retryBackoff);
    }

    KakaoMobilityClient(RestClient restClient, String restKey, int maxAttempts,
            Duration retryBackoff) {
        this.restClient = restClient;
        this.restKey = restKey;
        this.maxAttempts = Math.max(1, Math.min(maxAttempts, 3));
        this.backoffMillis = Math.max(0, retryBackoff.toMillis());
    }

    private static RestClient createRestClient(RestClient.Builder builder, String baseUrl,
            Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    Route route(List<RoutePoint> points) {
        return route(points, maxAttempts);
    }

    /** Diagnostic legs and semantic repairs must never multiply transient retries. */
    Route routeOnce(List<RoutePoint> points) {
        return route(points, 1);
    }

    private Route route(List<RoutePoint> points, int attemptLimit) {
        if (restKey == null || restKey.isBlank()) {
            throw new CourseCarRouteException("Kakao Mobility REST key is not configured.");
        }
        if (points.size() < 2 || points.size() > 7) {
            throw new IllegalArgumentException("A car route needs 2-7 ordered points.");
        }
        String waypoints = points.size() <= 2 ? null : points.subList(1, points.size() - 1)
                .stream().map(this::coordinate).reduce((a, b) -> a + "|" + b).orElse(null);
        String uri = UriComponentsBuilder.fromPath(DIRECTIONS_PATH)
                .queryParam("origin", coordinate(points.get(0)))
                .queryParam("destination", coordinate(points.get(points.size() - 1)))
                .queryParamIfPresent("waypoints", java.util.Optional.ofNullable(waypoints))
                .queryParam("priority", "RECOMMEND").queryParam("alternatives", false)
                .queryParam("road_details", false).queryParam("summary", false)
                .build().toUriString(); // RestClient encodes the URI template once.

        for (int attempt = 1; attempt <= attemptLimit; attempt++) {
            try {
                log.info("CAR_ROUTE_OUTBOUND attempt={} points={} waypoints={}", attempt, points.size(), Math.max(0, points.size() - 2));
                JsonNode response = restClient.get().uri(uri)
                        .header("Authorization", "KakaoAK " + restKey)
                        .retrieve().body(JsonNode.class);
                return parse(response, points);
            } catch (RestClientResponseException exception) {
                log.warn("CAR_ROUTE_HTTP_FAILURE attempt={} status={}", attempt, exception.getStatusCode().value());
                if (!retryable(exception.getStatusCode().value()) || attempt == attemptLimit) {
                    throw new CourseCarRouteException("Kakao Mobility route request failed (status="
                            + exception.getStatusCode().value() + ").", exception);
                }
                pause(attempt);
            } catch (ResourceAccessException exception) {
                log.warn("CAR_ROUTE_NETWORK_FAILURE attempt={} cause={}", attempt,
                        exception.getMostSpecificCause().getClass().getSimpleName());
                if (attempt == attemptLimit) {
                    throw new CourseCarRouteException("Kakao Mobility route request timed out.", exception);
                }
                pause(attempt);
            }
        }
        throw new CourseCarRouteException("Kakao Mobility route request failed.");
    }

    private Route parse(JsonNode response, List<RoutePoint> points) {
        JsonNode route = response == null ? null : response.path("routes").path(0);
        if (route != null && route.path("result_code").isIntegralNumber()
                && route.path("result_code").asInt() != 0) {
            log.warn("CAR_ROUTE_PROVIDER_FAILURE result_code={}", route.path("result_code").asInt());
            int code = route.path("result_code").asInt();
            if (code >= 101 && code <= 104) throw new RouteCoordinateException(code);
            throw new CourseCarRouteException("Kakao Mobility route is unavailable.");
        }
        JsonNode summary = route == null ? null : route.path("summary");
        JsonNode sections = route == null ? null : route.path("sections");
        if (summary == null || !summary.isObject() || sections == null || !sections.isArray()
                || sections.size() != points.size() - 1) {
            throw new CourseCarRouteException("Kakao Mobility returned an invalid route contract.");
        }
        List<RouteSection> parsed = new ArrayList<>();
        for (JsonNode section : sections) {
            List<Coordinate> vertices = new ArrayList<>();
            if (section.path("roads").isArray()) {
                for (JsonNode road : section.path("roads")) {
                    JsonNode values = road.path("vertexes");
                    if (!values.isArray() || values.size() % 2 != 0) continue;
                    for (int i = 0; i < values.size(); i += 2) {
                        vertices.add(new Coordinate(values.get(i + 1).asDouble(), values.get(i).asDouble()));
                    }
                }
            }
            if (vertices.isEmpty()) {
                throw new CourseCarRouteException(
                        "Kakao Mobility response is missing road vertices.");
            }
            parsed.add(new RouteSection(requiredInt(section, "distance"),
                    requiredInt(section, "duration"), List.copyOf(vertices)));
        }
        return new Route(requiredInt(summary, "distance"), requiredInt(summary, "duration"),
                List.copyOf(parsed));
    }

    private int requiredInt(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.canConvertToInt() || value.asInt() <= 0) {
            throw new CourseCarRouteException("Kakao Mobility response is missing " + field + ".");
        }
        return value.asInt();
    }
    private boolean retryable(int status) { return status == 429 || status >= 500; }
    private void pause(int attempt) {
        if (backoffMillis == 0) return;
        try { Thread.sleep(Math.min(backoffMillis * attempt, 1000)); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CourseCarRouteException("Kakao Mobility retry was interrupted.", e);
        }
    }
    private String coordinate(RoutePoint p) { return p.longitude() + "," + p.latitude(); }

    record RoutePoint(String type, String id, String name, double latitude, double longitude) {}
    record Route(int distanceMeters, int durationSeconds, List<RouteSection> sections) {}
    record RouteSection(int distanceMeters, int durationSeconds, List<Coordinate> polyline) {}
}
