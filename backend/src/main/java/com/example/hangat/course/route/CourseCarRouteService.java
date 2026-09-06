package com.example.hangat.course.route;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.common.model.BaseResponseStatus;
import com.example.hangat.course.model.AccommodationDto;
import com.example.hangat.course.model.CourseDetailResponse;
import com.example.hangat.course.model.enums.Transport;
import com.example.hangat.course.route.CarRouteResponse.Coordinate;
import com.example.hangat.course.route.KakaoMobilityClient.RoutePoint;
import com.example.hangat.course.service.CourseQueryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CourseCarRouteService {
    private static final String PROVIDER = "KAKAO_MOBILITY";
    private static final String PRIORITY = "RECOMMEND";
    private final CourseQueryService queryService;
    private final KakaoMobilityClient client;
    private final RouteAccessPointResolver accessResolver;
    private final long ttlMillis;
    private final int maxEntries;
    private final Map<String, CacheEntry> cache = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true));
    private final ConcurrentHashMap<String, CompletableFuture<CarRouteResponse>> inFlight =
            new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public CourseCarRouteService(CourseQueryService queryService, KakaoMobilityClient client,
            RouteAccessPointResolver accessResolver,
            @Value("${kakao-mobility.cache-ttl:10m}") Duration ttl,
            @Value("${kakao-mobility.cache-max-size:128}") int maxEntries) {
        this.queryService = queryService;
        this.client = client;
        this.accessResolver = accessResolver;
        this.ttlMillis = Math.max(1000, ttl.toMillis());
        this.maxEntries = Math.max(1, maxEntries);
    }

    CourseCarRouteService(CourseQueryService queryService, KakaoMobilityClient client, Duration ttl, int maxEntries) {
        this(queryService, client, null, ttl, maxEntries);
    }

    public CarRouteResponse route(Long courseId, Long authUserId) {
        CourseDetailResponse course = queryService.detail(courseId, authUserId);
        if (course.transport() != Transport.RENTAL_CAR) {
            throw new BaseException(BaseResponseStatus.COURSE_INVALID_CONDITION);
        }
        String key = signature(course);
        CacheEntry hit = cached(key);
        if (hit != null) return hit.value.withCached(true);

        CompletableFuture<CarRouteResponse> own = new CompletableFuture<>();
        CompletableFuture<CarRouteResponse> running = inFlight.putIfAbsent(key, own);
        if (running != null) {
            try { return running.join(); }
            catch (CompletionException exception) { throw unwrap(exception); }
        }
        try {
            CarRouteResponse value = calculate(course);
            if (complete(value)) put(key + "|access:" + value.days().stream().flatMap(d -> d.legs().stream())
                    .flatMap(l -> java.util.stream.Stream.of(l.from(), l.to()))
                    .map(CarRouteResponse.Stop::accessPoint).filter(java.util.Objects::nonNull)
                    .map(a -> a.sourceCode() + ":" + a.sourcePlaceId() + "@" + a.latitude() + "," + a.longitude())
                    .distinct().sorted().toList(), value);
            own.complete(value);
            return value;
        } catch (RuntimeException exception) {
            own.completeExceptionally(exception);
            throw exception;
        } finally {
            inFlight.remove(key, own);
        }
    }

    private CarRouteResponse calculate(CourseDetailResponse course) {
        List<CarRouteResponse.DayRoute> days = new ArrayList<>();
        Map<Long, java.util.Optional<CarRouteResponse.AccessPoint>> resolved = new java.util.HashMap<>();
        RouteCoordinateException unavailableDay = null;
        for (CourseDetailResponse.DayDto day : course.days()) {
            List<RoutePoint> points = points(day, course.accommodation());
            try {
            if (points.size() < 2) {
                days.add(new CarRouteResponse.DayRoute(day.dayNo(), day.visitDate(), null, null,
                        List.of(), List.of()));
                continue;
            }
            Map<Integer, CarRouteResponse.AccessPoint> accessPoints = new java.util.HashMap<>();
            KakaoMobilityClient.Route route;
            try {
                route = client.route(points);
            } catch (RouteCoordinateException failure) {
                if (failure.resultCode() == 101 && points.size() > 2) {
                    days.add(diagnose(day, points, accessPoints, resolved));
                    continue;
                }
                int index = failure.resultCode() == 102 ? 0 : failure.resultCode() == 103 ? points.size() - 1 : -1;
                if (index < 0 || !resolve(day, points, index, accessPoints, resolved)) {
                    if (points.size() <= 2) throw failure;
                    unavailableDay = failure;
                    days.add(diagnose(day, points, accessPoints, resolved));
                    continue;
                }
                try {
                    route = client.routeOnce(points);
                } catch (RouteCoordinateException repairedFailure) {
                    if (points.size() <= 2) throw repairedFailure;
                    if (repairedFailure.resultCode() != 101) unavailableDay = repairedFailure;
                    days.add(diagnose(day, points, accessPoints, resolved));
                    continue;
                }
            }
            List<CarRouteResponse.Leg> legs = new ArrayList<>();
            List<Coordinate> polyline = new ArrayList<>();
            for (int i = 0; i < route.sections().size(); i++) {
                KakaoMobilityClient.RouteSection section = route.sections().get(i);
                legs.add(new CarRouteResponse.Leg(stop(points.get(i), accessPoints.get(i)), stop(points.get(i + 1), accessPoints.get(i + 1)),
                        section.distanceMeters(), section.durationSeconds(), section.polyline()));
                for (Coordinate coordinate : section.polyline()) {
                    if (polyline.isEmpty() || !polyline.get(polyline.size() - 1).equals(coordinate)) {
                        polyline.add(coordinate);
                    }
                }
            }
            days.add(new CarRouteResponse.DayRoute(day.dayNo(), day.visitDate(),
                    route.distanceMeters(), route.durationSeconds(), List.copyOf(legs),
                    List.copyOf(polyline)));
            } catch (RouteCoordinateException failure) {
                // A provider business error belongs to this day, not to the whole itinerary.
                unavailableDay = failure;
                List<CarRouteResponse.Leg> missing = new ArrayList<>();
                for (int i = 0; i < points.size() - 1; i++) {
                    missing.add(new CarRouteResponse.Leg(stop(points.get(i), null),
                            stop(points.get(i + 1), null), null, null, List.of()));
                }
                days.add(new CarRouteResponse.DayRoute(day.dayNo(), day.visitDate(),
                        null, null, List.copyOf(missing), List.of()));
            }
        }
        if (unavailableDay != null && days.stream().flatMap(d -> d.legs().stream())
                .noneMatch(l -> l.distanceMeters() != null && l.durationSeconds() != null)) throw unavailableDay;
        return new CarRouteResponse(course.id(), course.transport(), PROVIDER, PRIORITY, false,
                OffsetDateTime.now(), List.copyOf(days));
    }

    // Kakao accepts at most 7 points: batch + optional repair + at most 6 * 2 leg calls.
    static final int MAX_DIAGNOSTIC_LEGS = 6;
    static final int MAX_LEG_ATTEMPTS = 2;

    private boolean complete(CarRouteResponse response) {
        return response.days().stream().flatMap(d -> d.legs().stream())
                .allMatch(l -> l.distanceMeters() != null && l.durationSeconds() != null);
    }

    private boolean resolve(CourseDetailResponse.DayDto day, List<RoutePoint> points, int index,
            Map<Integer, CarRouteResponse.AccessPoint> access,
            Map<Long, java.util.Optional<CarRouteResponse.AccessPoint>> resolved) {
        var point = points.get(index);
        if (accessResolver == null || access.containsKey(index) || !"COURSE_ITEM".equals(point.type())) return false;
        Long id = day.items().stream().filter(i -> String.valueOf(i.id()).equals(point.id()))
                .map(CourseDetailResponse.ItemDto::placeId).filter(java.util.Objects::nonNull).findFirst().orElse(null);
        if (id == null) return false;
        var result = resolved.computeIfAbsent(id, key -> {
            try { return accessResolver.resolve(key); }
            catch (RuntimeException unavailable) { return java.util.Optional.empty(); }
        });
        if (result.isEmpty()) return false;
        var a = result.get();
        points.set(index, new RoutePoint(point.type(), point.id(), point.name(), a.latitude(), a.longitude()));
        access.put(index, a);
        return true;
    }

    private CarRouteResponse.DayRoute diagnose(CourseDetailResponse.DayDto day, List<RoutePoint> points,
            Map<Integer, CarRouteResponse.AccessPoint> access,
            Map<Long, java.util.Optional<CarRouteResponse.AccessPoint>> resolved) {
        if (points.size() - 1 > MAX_DIAGNOSTIC_LEGS) throw new CourseCarRouteException("Too many diagnostic legs.");
        List<CarRouteResponse.Leg> legs = new ArrayList<>();
        List<Coordinate> line = new ArrayList<>();
        int meters = 0, seconds = 0;
        boolean all = true;
        for (int i = 0; i < points.size() - 1; i++) {
            // Reuse an already resolved endpoint without performing a new Local search.
            for (int endpoint : new int[]{i, i + 1}) {
                var point = points.get(endpoint);
                Long id = day.items().stream().filter(it -> String.valueOf(it.id()).equals(point.id()))
                        .map(CourseDetailResponse.ItemDto::placeId).findFirst().orElse(null);
                if (id != null && resolved.containsKey(id)) resolve(day, points, endpoint, access, resolved);
            }
            KakaoMobilityClient.Route leg = null;
            try {
                leg = client.routeOnce(List.of(points.get(i), points.get(i + 1)));
            } catch (RouteCoordinateException failure) {
                int endpoint = failure.resultCode() == 102 ? i : failure.resultCode() == 103 ? i + 1 : -1;
                if (endpoint >= 0 && resolve(day, points, endpoint, access, resolved)) {
                    try { leg = client.routeOnce(List.of(points.get(i), points.get(i + 1))); }
                    catch (CourseCarRouteException unavailable) { /* This leg remains missing. */ }
                }
            } catch (CourseCarRouteException unavailable) { /* Do not turn transport errors into access searches. */ }
            if (leg == null) {
                all = false;
                legs.add(new CarRouteResponse.Leg(stop(points.get(i), null), stop(points.get(i + 1), null), null, null, List.of()));
                continue;
            }
            var section = leg.sections().get(0);
            meters += section.distanceMeters();
            seconds += section.durationSeconds();
            legs.add(new CarRouteResponse.Leg(stop(points.get(i), access.get(i)), stop(points.get(i + 1), access.get(i + 1)),
                    section.distanceMeters(), section.durationSeconds(), section.polyline()));
            for (var coordinate : section.polyline()) {
                if (line.isEmpty() || !line.get(line.size() - 1).equals(coordinate)) line.add(coordinate);
            }
        }
        // Never join successful lines across a missing leg into a fictitious road.
        return new CarRouteResponse.DayRoute(day.dayNo(), day.visitDate(), all ? meters : null,
                all ? seconds : null, List.copyOf(legs), all ? List.copyOf(line) : List.of());
    }

    private List<RoutePoint> points(CourseDetailResponse.DayDto day, AccommodationDto accommodation) {
        List<RoutePoint> points = new ArrayList<>();
        RoutePoint stay = accommodationPoint(accommodation);
        if (stay != null) points.add(stay);
        for (CourseDetailResponse.ItemDto item : day.items()) {
            if (!valid(item.latitude(), item.longitude())) {
                throw new CourseCarRouteException("Course contains an invalid route coordinate.");
            }
            points.add(new RoutePoint("COURSE_ITEM", String.valueOf(item.id()), item.placeName(),
                    item.latitude(), item.longitude()));
        }
        if (stay != null && !day.items().isEmpty()) points.add(stay);
        return points;
    }

    private RoutePoint accommodationPoint(AccommodationDto accommodation) {
        if (accommodation == null) return null;
        if (!valid(accommodation.getLatitude(), accommodation.getLongitude())) {
            throw new CourseCarRouteException("Accommodation contains an invalid route coordinate.");
        }
        return new RoutePoint("ACCOMMODATION", accommodation.getSourcePlaceId(),
                accommodation.getPlaceName(), accommodation.getLatitude(), accommodation.getLongitude());
    }

    private boolean valid(Double latitude, Double longitude) {
        return latitude != null && longitude != null && latitude >= 32.9 && latitude <= 33.7
                && longitude >= 126.0 && longitude <= 127.1;
    }
    private CarRouteResponse.Stop stop(RoutePoint p, CarRouteResponse.AccessPoint access) {
        return new CarRouteResponse.Stop(p.type(), p.id(), p.name(), access);
    }

    private CacheEntry cached(String key) {
        synchronized (cache) {
            String resolvedKey = cache.keySet().stream().filter(k -> k.startsWith(key + "|access:")).findFirst().orElse(null);
            CacheEntry entry = resolvedKey == null ? null : cache.get(resolvedKey);
            if (entry == null) return null;
            if (entry.expiresAt < System.currentTimeMillis()) { cache.remove(resolvedKey); return null; }
            return entry;
        }
    }
    private void put(String key, CarRouteResponse value) {
        synchronized (cache) {
            cache.put(key, new CacheEntry(value, System.currentTimeMillis() + ttlMillis));
            while (cache.size() > maxEntries) cache.remove(cache.keySet().iterator().next());
        }
    }
    private String signature(CourseDetailResponse course) {
        StringBuilder raw = new StringBuilder(PRIORITY).append('|').append(course.transport());
        if (course.accommodation() != null) {
            raw.append("|stay:").append(course.accommodation().getSourceCode()).append(':')
                    .append(course.accommodation().getSourcePlaceId()).append('@')
                    .append(course.accommodation().getLongitude()).append(',')
                    .append(course.accommodation().getLatitude());
        }
        for (CourseDetailResponse.DayDto day : course.days()) {
            raw.append('|').append(day.visitDate());
            for (CourseDetailResponse.ItemDto item : day.items()) {
                raw.append('>').append(item.id()).append(':').append(item.placeId()).append('@').append(item.longitude())
                        .append(',').append(item.latitude());
            }
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private RuntimeException unwrap(CompletionException e) {
        return e.getCause() instanceof RuntimeException runtime ? runtime : e;
    }
    private record CacheEntry(CarRouteResponse value, long expiresAt) {}
}
