package com.example.hangat.domain.alternative;

import com.example.hangat.course.route.AlternativeRoadDistance;
import com.example.hangat.course.route.AlternativeRoadDistance.Point;
import com.example.hangat.course.route.CourseCarRouteException;
import com.example.hangat.domain.alternative.model.AlternativePlaceResponse;
import com.example.hangat.map.model.enums.CongestionLevel;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoadAlternativeServiceTest {
    @Test void roadOrderIsNotStraightOrderAndTwentyKmIsInclusiveAndCached() {
        var data = mock(AlternativeService.class); var roads = mock(AlternativeRoadDistance.class);
        var origin = new Point(1L, 33.4, 126.4);
        var input = new AlternativeService.RoadInput(LocalDate.of(2026,9,18), origin,
                List.of(candidate(2L), candidate(3L), candidate(4L), candidate(5L)));
        when(data.roadInput(1L)).thenReturn(input);
        when(roads.distance(eq(origin), any())).thenAnswer(call -> switch (((Point)call.getArgument(1)).id().intValue()) {
            case 2 -> OptionalInt.of(19000); case 3 -> OptionalInt.of(5000);
            case 4 -> OptionalInt.of(20000); default -> OptionalInt.of(20001);
        });
        try (var service = new RoadAlternativeService(data, roads)) {
            var response = service.alternatives(1L, Set.of());
            assertThat(response.places()).extracting(AlternativePlaceResponse::placeId).containsExactly(3L,2L,4L);
            assertThat(response.places()).extracting(AlternativePlaceResponse::distanceM).containsExactly(5000,19000,20000);
            assertThat(service.alternatives(1L, Set.of(3L)).places()).extracting(AlternativePlaceResponse::placeId).containsExactly(2L,4L);
            verify(roads, times(4)).distance(eq(origin), any());
        }
    }
    @Test void transientFailureIsNotAnEmptySuccessAndCanBeRetried() {
        var data = mock(AlternativeService.class); var roads = mock(AlternativeRoadDistance.class);
        var origin = new Point(1L,33.4,126.4);
        when(data.roadInput(1L)).thenReturn(new AlternativeService.RoadInput(LocalDate.of(2026,9,18),origin,List.of(candidate(2L))));
        when(roads.distance(any(),any())).thenThrow(new CourseCarRouteException("quota")) .thenReturn(OptionalInt.of(1234));
        try (var service = new RoadAlternativeService(data,roads)) {
            assertThatThrownBy(() -> service.alternatives(1L,Set.of())).isInstanceOf(CourseCarRouteException.class);
            assertThat(service.alternatives(1L,Set.of()).places()).hasSize(1);
        }
    }
    private AlternativeService.RoadCandidate candidate(long id) {
        return new AlternativeService.RoadCandidate(new AlternativePlaceResponse(id,"장소"+id,"관광지","서부",null,"DB 소개",1000,20,
                CongestionLevel.QUIET,"한산",10,"",""),new Point(id,33.4+id*.001,126.4));
    }
}
