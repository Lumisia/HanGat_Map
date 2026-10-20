package com.example.hangat.domain.alternative;

import com.example.hangat.course.route.AlternativeRoadDistance;
import com.example.hangat.course.route.CourseCarRouteException;
import com.example.hangat.domain.alternative.model.AlternativePlaceResponse;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

/** A complete, bounded road-ranked snapshot. No top-three straight-line approximation. */
@Service
public class RoadAlternativeService implements AutoCloseable {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Response(LocalDate forecastDate, String distanceBasis, List<AlternativePlaceResponse> places, int unavailableCount) {}
    private record Cached(long expiresAt, Response response) {}
    private final AlternativeService data;
    private final AlternativeRoadDistance roads;
    private final Map<AlternativeService.RoadInput, Cached> cache = new LinkedHashMap<>(16,.75f,true);
    private final ConcurrentHashMap<AlternativeService.RoadInput, CompletableFuture<Response>> running = new ConcurrentHashMap<>();
    // Globally bounded: at most two candidate batches and four outbound distance calls at once.
    private final Semaphore batches = new Semaphore(2);
    private final ExecutorService workers = Executors.newFixedThreadPool(4);

    public RoadAlternativeService(AlternativeService data, AlternativeRoadDistance roads) {
        this.data = data; this.roads = roads;
    }

    public Response alternatives(Long placeId, Set<Long> exclude) {
        var input = data.roadInput(placeId);
        Response result = snapshot(input);
        return new Response(result.forecastDate(), result.distanceBasis(),
                result.places().stream().filter(p -> !exclude.contains(p.placeId())).toList(), result.unavailableCount());
    }

    private Response snapshot(AlternativeService.RoadInput input) {
        synchronized (cache) {
            cache.entrySet().removeIf(e -> e.getValue().expiresAt() <= System.currentTimeMillis());
            var hit = cache.get(input);
            if (hit != null) return hit.response();
        }
        var own = new CompletableFuture<Response>();
        var pending = running.putIfAbsent(input, own);
        if (pending != null) {
            try { return pending.get(65,TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new CourseCarRouteException("Road lookup interrupted.",e); }
            catch (ExecutionException | TimeoutException e) { throw new CourseCarRouteException("Road lookup unavailable.",e); }
        }
        boolean acquired = batches.tryAcquire();
        try {
            if (!acquired) throw new CourseCarRouteException("Road lookup busy. Retry later.");
            Response result = calculate(input);
            synchronized (cache) {
                cache.put(input,new Cached(System.currentTimeMillis()+600_000,result));
                while (cache.size()>128) cache.remove(cache.keySet().iterator().next());
            }
            own.complete(result);
            return result;
        } catch (RuntimeException failure) {
            own.completeExceptionally(failure); throw failure;
        } finally {
            if (acquired) batches.release();
            running.remove(input,own);
        }
    }

    private Response calculate(AlternativeService.RoadInput input) {
        List<Future<OptionalInt>> futures = new ArrayList<>();
        long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
        try {
            for (var candidate : input.candidates())
                futures.add(workers.submit(() -> roads.distance(input.origin(),candidate.point())));
            List<AlternativePlaceResponse> result = new ArrayList<>();
            int unavailable = 0;
            for (int i=0;i<futures.size();i++) {
                var distance = futures.get(i).get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
                if (distance.isEmpty()) { unavailable++; continue; }
                int metres = distance.getAsInt();
                if (metres > 20_000) continue;
                var p = input.candidates().get(i).place();
                result.add(new AlternativePlaceResponse(p.placeId(),p.placeName(),p.categoryName(),p.regionName(),
                        p.imageUrl(),p.overview(),metres,p.congestionRate(),p.congestionLevel(),p.congestionLabel(),
                        metres<=10_000 ? 10 : 20,p.recommendationReason(),"자동차 도로거리 기준 대안"));
            }
            if (!input.candidates().isEmpty() && unavailable == input.candidates().size())
                throw new CourseCarRouteException("No candidate has a verifiable car route.");
            result.sort(Comparator.comparingInt(AlternativePlaceResponse::distanceM).thenComparing(AlternativePlaceResponse::placeId));
            return new Response(input.date(),"CAR_ROAD",List.copyOf(result),unavailable);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new CourseCarRouteException("Road lookup interrupted.",e);
        } catch (ExecutionException | TimeoutException e) {
            throw new CourseCarRouteException("Could not verify all road distances. Retry later.",e);
        } finally { futures.forEach(f -> { if (!f.isDone()) f.cancel(true); }); }
    }

    @PreDestroy @Override public void close() { workers.shutdownNow(); }
}
