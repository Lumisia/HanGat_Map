package com.example.hangat.course.route;

import org.springframework.stereotype.Service;
import java.util.OptionalInt;

/** Uses the same validated entrance/parking resolver as saved-course car routing. */
@Service
public class AlternativeRoadDistance {
    public record Point(Long id, double latitude, double longitude) {}
    private final KakaoMobilityClient client;
    private final RouteAccessPointResolver access;
    public AlternativeRoadDistance(KakaoMobilityClient client, RouteAccessPointResolver access) {
        this.client = client; this.access = access;
    }
    public OptionalInt distance(Point from, Point to) {
        var origin = routePoint(from); var destination = routePoint(to);
        try { return OptionalInt.of(client.shortestDistance(origin, destination)); }
        catch (RouteCoordinateException failure) {
            boolean repaired = false;
            if (failure.resultCode() == 101 || failure.resultCode() == 102) {
                var resolved = access.resolve(from.id());
                if (resolved.isPresent()) {
                    var p = resolved.get(); origin = routePoint(new Point(from.id(), p.latitude(), p.longitude())); repaired = true;
                } else if (failure.resultCode() == 102) {
                    throw new CourseCarRouteException("The origin has no verified car access point.");
                }
            }
            if (failure.resultCode() == 101 || failure.resultCode() == 103) {
                var resolved = access.resolve(to.id());
                if (resolved.isPresent()) {
                    var p = resolved.get(); destination = routePoint(new Point(to.id(), p.latitude(), p.longitude())); repaired = true;
                }
            }
            if (repaired) {
                try { return OptionalInt.of(client.shortestDistance(origin, destination)); }
                catch (RouteCoordinateException unavailable) {
                    if (unavailable.resultCode() == 102) throw new CourseCarRouteException("The origin is not car accessible.");
                }
            }
            return OptionalInt.empty(); // Only a provider no-route outcome, never HTTP/network failure.
        }
    }
    private KakaoMobilityClient.RoutePoint routePoint(Point p) {
        return new KakaoMobilityClient.RoutePoint("PLACE", p.id().toString(), "", p.latitude(), p.longitude());
    }
}
