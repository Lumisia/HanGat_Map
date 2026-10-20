package com.example.hangat.course.route;

import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AlternativeRoadDistanceTest {
    @Test void networkFailureIsNeverConvertedIntoNoCandidate() {
        var client=mock(KakaoMobilityClient.class); var access=mock(RouteAccessPointResolver.class);
        when(client.shortestDistance(any(),any())).thenThrow(new CourseCarRouteException("timeout"));
        assertThatThrownBy(()->new AlternativeRoadDistance(client,access).distance(
                new AlternativeRoadDistance.Point(1L,33.4,126.4),new AlternativeRoadDistance.Point(2L,33.5,126.5)))
                .isInstanceOf(CourseCarRouteException.class);
        verifyNoInteractions(access);
    }
    @Test void destinationNoRouteIsExcludedButOriginNoRouteIsAnError() {
        var client=mock(KakaoMobilityClient.class); var access=mock(RouteAccessPointResolver.class);
        when(access.resolve(any())).thenReturn(Optional.empty());
        var service=new AlternativeRoadDistance(client,access);
        var origin=new AlternativeRoadDistance.Point(1L,33.4,126.4);
        var destination=new AlternativeRoadDistance.Point(2L,33.5,126.5);
        when(client.shortestDistance(any(),any())).thenThrow(new RouteCoordinateException(103));
        assertThat(service.distance(origin,destination)).isEmpty();
        doThrow(new RouteCoordinateException(102)).when(client).shortestDistance(any(),any());
        assertThatThrownBy(()->service.distance(origin,destination)).isInstanceOf(CourseCarRouteException.class);
    }
}
