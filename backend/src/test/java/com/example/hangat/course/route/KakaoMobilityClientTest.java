package com.example.hangat.course.route;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KakaoMobilityClientTest {
    @Test
    void businessCoordinateFailuresNeverRetry() {
        for (int code : new int[]{101,102,103,104}) {
            var builder=RestClient.builder();
            var server=MockRestServiceServer.bindTo(builder).build();
            server.expect(times(1),method(HttpMethod.GET)).andRespond(withSuccess(
                    "{\"routes\":[{\"result_code\":"+code+"}]}",MediaType.APPLICATION_JSON));
            assertThatThrownBy(() -> client(builder,3).route(points())).isInstanceOf(RouteCoordinateException.class)
                    .satisfies(e -> assertThat(((RouteCoordinateException)e).resultCode()).isEqualTo(code));
            server.verify();
        }
    }

    @Test
    void encodesMultipleWaypointsExactlyOnce() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(method(HttpMethod.GET))
                .andExpect(request -> assertThat(request.getURI().getRawQuery())
                        .contains("waypoints=126.56,33.25%7C126.55,33.26")
                        .doesNotContain("%257C"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));
        var ordered = new java.util.ArrayList<>(points());
        ordered.add(2, new KakaoMobilityClient.RoutePoint("COURSE_ITEM", "4", "middle", 33.26, 126.55));
        assertThatThrownBy(() -> client(builder, 1).route(ordered))
                .isInstanceOf(CourseCarRouteException.class);
        server.verify();
    }

    @Test
    void sendsLongitudeThenLatitudeAndParsesSectionsAndVertices() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KakaoMobilityClient client = client(builder, 1);
        server.expect(method(HttpMethod.GET))
                .andExpect(queryParam("origin", "126.57,33.24"))
                .andExpect(queryParam("waypoints", "126.56,33.25"))
                .andExpect(queryParam("destination", "126.53,33.52"))
                .andExpect(queryParam("priority", "RECOMMEND"))
                .andExpect(header("Authorization", "KakaoAK test-key"))
                .andRespond(withSuccess("""
                        {"routes":[{"summary":{"distance":42000,"duration":3600},"sections":[
                          {"distance":1000,"duration":120,"roads":[{"vertexes":[126.57,33.24,126.56,33.25]}]},
                          {"distance":41000,"duration":3480,"roads":[{"vertexes":[126.56,33.25,126.53,33.52]}]}
                        ]}]}
                        """, MediaType.APPLICATION_JSON));

        KakaoMobilityClient.Route route = client.route(points());

        assertThat(route.distanceMeters()).isEqualTo(42000);
        assertThat(route.durationSeconds()).isEqualTo(3600);
        assertThat(route.sections()).hasSize(2);
        assertThat(route.sections().get(0).polyline().get(0).latitude()).isEqualTo(33.24);
        assertThat(route.sections().get(0).polyline().get(0).longitude()).isEqualTo(126.57);
        server.verify();
    }

    @Test
    void retriesTransientStatusButDoesNotRetryPermanentClientError() {
        RestClient.Builder transientBuilder = RestClient.builder();
        MockRestServiceServer transientServer = MockRestServiceServer.bindTo(transientBuilder).build();
        transientServer.expect(times(2), method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> client(transientBuilder, 2).route(points()))
                .isInstanceOf(CourseCarRouteException.class);
        transientServer.verify();

        RestClient.Builder permanentBuilder = RestClient.builder();
        MockRestServiceServer permanentServer = MockRestServiceServer.bindTo(permanentBuilder).build();
        permanentServer.expect(times(1), method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> client(permanentBuilder, 3).route(points()))
                .isInstanceOf(CourseCarRouteException.class);
        permanentServer.verify();
    }

    @Test
    void rejectsZeroDistanceOrMissingRoadVerticesAsUnavailable() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(method(HttpMethod.GET)).andRespond(withSuccess("""
                {"routes":[{"summary":{"distance":0,"duration":0},"sections":[
                  {"distance":0,"duration":0,"roads":[]},
                  {"distance":0,"duration":0,"roads":[]}
                ]}]}
                """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client(builder, 1).route(points()))
                .isInstanceOf(CourseCarRouteException.class);
        server.verify();
    }

    private KakaoMobilityClient client(RestClient.Builder builder, int attempts) {
        return new KakaoMobilityClient(builder.baseUrl("https://mobility.test").build(),
                "test-key", attempts, Duration.ZERO);
    }

    private List<KakaoMobilityClient.RoutePoint> points() {
        return List.of(
                new KakaoMobilityClient.RoutePoint("COURSE_ITEM", "1", "south", 33.24, 126.57),
                new KakaoMobilityClient.RoutePoint("COURSE_ITEM", "2", "south2", 33.25, 126.56),
                new KakaoMobilityClient.RoutePoint("COURSE_ITEM", "3", "north", 33.52, 126.53));
    }
}
