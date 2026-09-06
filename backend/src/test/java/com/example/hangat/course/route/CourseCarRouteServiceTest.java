package com.example.hangat.course.route;

import com.example.hangat.course.model.CourseDetailResponse;
import com.example.hangat.course.model.enums.Transport;
import com.example.hangat.course.service.CourseQueryService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CourseCarRouteServiceTest {
    @Test
    void noAccommodationUsesOnlyOrderedPlacesAndPreservesPartialLegs() {
        for (int code : new int[]{102, 103, 104}) {
            var query = mock(CourseQueryService.class);
            var client = mock(KakaoMobilityClient.class);
            var resolver = mock(RouteAccessPointResolver.class);
            var original = course(List.of(item(1,33.43,126.76),item(2,33.41,126.68),item(3,33.43,126.92)));
            when(query.detail(10L,null)).thenReturn(original);
            when(client.route(org.mockito.ArgumentMatchers.anyList())).thenThrow(new RouteCoordinateException(code));
            when(client.routeOnce(org.mockito.ArgumentMatchers.anyList()))
                    .thenThrow(new RouteCoordinateException(102)).thenReturn(success())
                    .thenThrow(new RouteCoordinateException(102)).thenReturn(success());
            var service = new CourseCarRouteService(query,client,resolver,Duration.ofMinutes(10),2);
            var before = List.copyOf(original.days().get(0).items());
            var result = service.route(10L,null);
            assertThat(result.days().get(0).totalDistanceMeters()).isNull();
            assertThat(result.days().get(0).legs().get(0).durationSeconds()).isNull();
            assertThat(result.days().get(0).legs().get(0).polyline()).isEmpty();
            assertThat(result.days().get(0).legs().get(1).distanceMeters()).isEqualTo(1000);
            assertThat(result.days().get(0).polyline()).isEmpty();
            assertThat(service.route(10L,null).cached()).isFalse();
            assertThat(original.days().get(0).items()).containsExactlyElementsOf(before);
            verify(client,times(2)).route(org.mockito.ArgumentMatchers.argThat(points ->
                    points.size()==3 && points.stream().allMatch(p->p.type().equals("COURSE_ITEM"))
                    && points.get(0).id().equals("1") && points.get(2).id().equals("3")));
        }
    }

    @Test
    void oneFailedDayDoesNotDiscardOtherDaysForEveryBusinessError() {
        for (int code : new int[]{101,102,103,104}) {
            var query=mock(CourseQueryService.class);var client=mock(KakaoMobilityClient.class);
            var original=course(List.of(item(1,33.24,126.57),item(2,33.25,126.56)));
            var days = List.of(
                    new CourseDetailResponse.DayDto(1,LocalDate.of(2026,9,3),List.of(item(1,33.24,126.57),item(2,33.25,126.56))),
                    new CourseDetailResponse.DayDto(2,LocalDate.of(2026,9,4),List.of(item(3,33.26,126.55),item(4,33.27,126.54))));
            when(original.days()).thenReturn(days);
            when(query.detail(10L,null)).thenReturn(original);
            when(client.route(org.mockito.ArgumentMatchers.anyList())).thenThrow(new RouteCoordinateException(code)).thenReturn(success());
            var result=new CourseCarRouteService(query,client,Duration.ofMinutes(10),2).route(10L,null);
            assertThat(result.days()).hasSize(2);
            assertThat(result.days().get(0).legs().get(0).distanceMeters()).isNull();
            assertThat(result.days().get(1).totalDistanceMeters()).isEqualTo(1000);
            assertThat(result.days().get(1).totalDurationSeconds()).isEqualTo(120);
        }
    }

    @Test
    void unresolvedEndpointWithNoSuccessfulLegRetainsFailure() {
        var query=mock(CourseQueryService.class);var client=mock(KakaoMobilityClient.class);
        var original=course(List.of(item(1,33.24,126.57),item(2,33.25,126.56),item(3,33.26,126.55)));
        when(query.detail(10L,null)).thenReturn(original);
        when(client.route(org.mockito.ArgumentMatchers.anyList())).thenThrow(new RouteCoordinateException(102));
        when(client.routeOnce(org.mockito.ArgumentMatchers.anyList())).thenThrow(new RouteCoordinateException(102));
        assertThatThrownBy(()->new CourseCarRouteService(query,client,Duration.ofMinutes(10),2).route(10L,null))
                .isInstanceOf(RouteCoordinateException.class);
    }

    @Test
    void accommodationAddsDepartureAndReturnAndInvalidatesSuccessfulCache() {
        var query=mock(CourseQueryService.class);var client=mock(KakaoMobilityClient.class);
        var original=course(List.of(item(1,33.24,126.57),item(2,33.25,126.56)));
        when(query.detail(10L,null)).thenReturn(original);
        when(client.route(org.mockito.ArgumentMatchers.anyList())).thenAnswer(invocation->{
            List<KakaoMobilityClient.RoutePoint> points=invocation.getArgument(0);
            return new KakaoMobilityClient.Route(1000*(points.size()-1),120*(points.size()-1),
                    java.util.Collections.nCopies(points.size()-1,success().sections().get(0)));
        });
        var service=new CourseCarRouteService(query,client,Duration.ofMinutes(10),2);
        assertThat(service.route(10L,null).cached()).isFalse();
        assertThat(service.route(10L,null).cached()).isTrue();
        var stay=mock(com.example.hangat.course.model.AccommodationDto.class);
        when(stay.getLatitude()).thenReturn(33.3);when(stay.getLongitude()).thenReturn(126.6);
        when(stay.getSourcePlaceId()).thenReturn("verified-stay");
        when(original.accommodation()).thenReturn(stay);
        assertThat(service.route(10L,null).cached()).isFalse();
        verify(client).route(org.mockito.ArgumentMatchers.argThat(p->p.size()==4
                &&p.get(0).type().equals("ACCOMMODATION")&&p.get(3).type().equals("ACCOMMODATION")));
    }

    private KakaoMobilityClient.Route success() {
        return new KakaoMobilityClient.Route(1000,120,List.of(new KakaoMobilityClient.RouteSection(
                1000,120,List.of(new CarRouteResponse.Coordinate(33.25,126.56)))));
    }
    @Test
    void observedYeolanjiRuntimeFailuresRemainMissingWithoutInventingAccess() {
        // Public runtime evidence: KTO:1925366, 33.4265967334/126.5207167107.
        // Eorimok -> Yeolanji = 103; Yeolanji -> Bangseonmun = 102; Local candidates = 0.
        var query=mock(CourseQueryService.class); var client=mock(KakaoMobilityClient.class);
        var resolver=mock(RouteAccessPointResolver.class);
        var original=course(List.of(item(1,33.3856593715,126.5067521811),
                item(2,33.4265967334,126.5207167107),item(3,33.4542936092,126.5169053039)));
        when(query.detail(10L,null)).thenReturn(original);
        when(client.route(org.mockito.ArgumentMatchers.anyList())).thenThrow(new RouteCoordinateException(102));
        when(client.routeOnce(org.mockito.ArgumentMatchers.anyList())).thenThrow(new RouteCoordinateException(101))
                .thenThrow(new RouteCoordinateException(103)).thenThrow(new RouteCoordinateException(102));
        when(resolver.resolve(101L)).thenReturn(java.util.Optional.of(new CarRouteResponse.AccessPoint(
                101L,"어승생","PARKING","어리목주차장",33.3920786624679,126.49461170461,1335,
                "KAKAO_LOCAL","20588448","어승생악 인근 어리목주차장까지 차량 경로")));
        when(resolver.resolve(102L)).thenReturn(java.util.Optional.empty());
        var result=new CourseCarRouteService(query,client,resolver,Duration.ofMinutes(10),2).route(10L,null);
        assertThat(result.days().get(0).legs()).hasSize(2).allSatisfy(l->{
            assertThat(l.distanceMeters()).isNull(); assertThat(l.durationSeconds()).isNull();
            assertThat(l.polyline()).isEmpty();
        });
        assertThat(result.days().get(0).totalDistanceMeters()).isNull();
        assertThat(result.days().get(0).polyline()).isEmpty();
        assertThat(original.days().get(0).items().get(1).latitude()).isEqualTo(33.4265967334);
        verify(resolver,times(1)).resolve(102L);
        verify(client,times(1)).route(org.mockito.ArgumentMatchers.anyList());
        verify(client,times(3)).routeOnce(org.mockito.ArgumentMatchers.anyList());
    }
    @Test
    void waypointDiagnosisReusesAccessAndCachesCompleteLegs() {
        var query=mock(CourseQueryService.class); var client=mock(KakaoMobilityClient.class);
        var resolver=mock(RouteAccessPointResolver.class);
        var original=course(List.of(item(1,33.24,126.57),item(2,33.25,126.56),item(3,33.26,126.55)));
        when(query.detail(10L,null)).thenReturn(original);
        when(client.route(org.mockito.ArgumentMatchers.anyList())).thenThrow(new RouteCoordinateException(101));
        var point=new CarRouteResponse.Coordinate(33.25,126.56);
        var section=new KakaoMobilityClient.RouteSection(1000,120,List.of(point));
        var success=new KakaoMobilityClient.Route(1000,120,List.of(section));
        when(client.routeOnce(org.mockito.ArgumentMatchers.anyList())).thenThrow(new RouteCoordinateException(103))
                .thenReturn(success,success);
        when(resolver.resolve(102L)).thenReturn(java.util.Optional.of(new CarRouteResponse.AccessPoint(
                102L,"place-2","PARKING","parking",33.251,126.561,100,"KAKAO_LOCAL","88","access")));
        var service=new CourseCarRouteService(query,client,resolver,Duration.ofMinutes(10),2);
        var result=service.route(10L,null);
        assertThat(result.days().get(0).totalDistanceMeters()).isEqualTo(2000);
        assertThat(result.days().get(0).totalDurationSeconds()).isEqualTo(240);
        assertThat(result.days().get(0).polyline()).containsExactly(point);
        assertThat(result.days().get(0).legs().get(0).to().accessPoint())
                .isEqualTo(result.days().get(0).legs().get(1).from().accessPoint());
        assertThat(result.days().get(0).legs()).extracting(l->l.from().id()).containsExactly("1","2");
        assertThat(service.route(10L,null).cached()).isTrue();
        verify(client,times(1)).route(org.mockito.ArgumentMatchers.anyList());
        verify(client,times(3)).routeOnce(org.mockito.ArgumentMatchers.anyList());
        verify(resolver,times(1)).resolve(102L);
        assertThat(original.days().get(0).items().get(1).latitude()).isEqualTo(33.25);
    }

    @Test
    void multipleWaypointsPartialLegsNeverProduceCompleteTotalsOrCache() {
        var query=mock(CourseQueryService.class); var client=mock(KakaoMobilityClient.class);
        var resolver=mock(RouteAccessPointResolver.class);
        var original=course(List.of(item(1,33.24,126.57),item(2,33.25,126.56),item(3,33.26,126.55),item(4,33.27,126.54)));
        when(query.detail(10L,null)).thenReturn(original);
        when(client.route(org.mockito.ArgumentMatchers.anyList())).thenThrow(new RouteCoordinateException(101));
        var success=new KakaoMobilityClient.Route(1000,120,List.of(new KakaoMobilityClient.RouteSection(1000,120,List.of())));
        when(client.routeOnce(org.mockito.ArgumentMatchers.anyList())).thenThrow(new RouteCoordinateException(103))
                .thenThrow(new RouteCoordinateException(102)).thenReturn(success)
                .thenThrow(new RouteCoordinateException(104)).thenReturn(success,success);
        when(resolver.resolve(102L)).thenReturn(java.util.Optional.empty());
        var service=new CourseCarRouteService(query,client,resolver,Duration.ofMinutes(10),2);
        var result=service.route(10L,null);
        assertThat(result.days().get(0).legs()).hasSize(3);
        assertThat(result.days().get(0).totalDistanceMeters()).isNull();
        assertThat(result.days().get(0).totalDurationSeconds()).isNull();
        assertThat(result.days().get(0).legs().get(0).distanceMeters()).isNull();
        assertThat(result.days().get(0).legs().get(2).distanceMeters()).isEqualTo(1000);
        assertThat(result.days().get(0).polyline()).isEmpty();
        assertThat(service.route(10L,null).cached()).isFalse();
        verify(resolver,times(1)).resolve(102L);
        verify(client,times(6)).routeOnce(org.mockito.ArgumentMatchers.anyList());
        assertThat(CourseCarRouteService.MAX_DIAGNOSTIC_LEGS).isEqualTo(6);
        assertThat(CourseCarRouteService.MAX_LEG_ATTEMPTS).isEqualTo(2);
    }
    @Test
    void resolvesOnlyKnownEndpointsOnceAndCachesAccessIdentity() {
        for (int code : new int[]{102,103}) {
            var query=mock(CourseQueryService.class); var client=mock(KakaoMobilityClient.class);
            var resolver=mock(RouteAccessPointResolver.class);
            var original=course(List.of(item(1,33.24,126.57),item(2,33.25,126.56)));
            when(query.detail(10L,null)).thenReturn(original);
            var access=new CarRouteResponse.AccessPoint(101L,"place","PARKING","place 주차장",33.241,126.571,100,"KAKAO_LOCAL","77","안내");
            when(resolver.resolve(org.mockito.ArgumentMatchers.anyLong())).thenReturn(java.util.Optional.of(access));
            when(client.route(org.mockito.ArgumentMatchers.anyList())).thenThrow(new RouteCoordinateException(code))
                    ;
            when(client.routeOnce(org.mockito.ArgumentMatchers.anyList())).thenReturn(new KakaoMobilityClient.Route(1000,120,List.of(new KakaoMobilityClient.RouteSection(1000,120,List.of()))));
            var service=new CourseCarRouteService(query,client,resolver,Duration.ofMinutes(10),2);
            var result=service.route(10L,null);
            var stop=code==102?result.days().get(0).legs().get(0).from():result.days().get(0).legs().get(0).to();
            assertThat(stop.accessPoint().sourcePlaceId()).isEqualTo("77");
            assertThat(service.route(10L,null).cached()).isTrue();
            verify(client,times(1)).route(org.mockito.ArgumentMatchers.anyList());
            verify(client,times(1)).routeOnce(org.mockito.ArgumentMatchers.anyList());
            verify(resolver,times(1)).resolve(code==102?101L:102L);
            assertThat(original.days().get(0).items().get(0).latitude()).isEqualTo(33.24);
        }
    }

    @Test
    void ambiguousWaypointAndSecondFailureDoNotSearchAgain() {
        for(int code:new int[]{101,104,102}) {
            var query=mock(CourseQueryService.class); var client=mock(KakaoMobilityClient.class);
            var resolver=mock(RouteAccessPointResolver.class);
            var original = course(List.of(item(1,33.24,126.57),item(2,33.25,126.56)));
            when(query.detail(10L,null)).thenReturn(original);
            when(client.route(org.mockito.ArgumentMatchers.anyList())).thenThrow(new RouteCoordinateException(code));
            when(client.routeOnce(org.mockito.ArgumentMatchers.anyList())).thenThrow(new RouteCoordinateException(code));
            when(resolver.resolve(101L)).thenReturn(java.util.Optional.of(new CarRouteResponse.AccessPoint(
                    101L,"place","PARKING","place 주차장",33.241,126.571,100,"KAKAO_LOCAL","77","안내")));
            var service=new CourseCarRouteService(query,client,resolver,Duration.ofMinutes(10),2);
            assertThatThrownBy(()->service.route(10L,null)).isInstanceOf(RouteCoordinateException.class);
            verify(resolver,times(code==102?1:0)).resolve(org.mockito.ArgumentMatchers.anyLong());
            verify(client,times(1)).route(org.mockito.ArgumentMatchers.anyList());
            verify(client,times(code==102?1:0)).routeOnce(org.mockito.ArgumentMatchers.anyList());
        }
    }

    @Test
    void failedRouteIsNotCachedAndDoesNotChangeCourse() {
        CourseQueryService query = mock(CourseQueryService.class);
        KakaoMobilityClient client = mock(KakaoMobilityClient.class);
        var original = course(List.of(item(1, 33.24, 126.57), item(2, 33.25, 126.56)));
        when(query.detail(10L, null)).thenReturn(original);
        when(client.route(org.mockito.ArgumentMatchers.anyList()))
                .thenThrow(new CourseCarRouteException("unavailable"));
        var service = new CourseCarRouteService(query, client, Duration.ofMinutes(10), 2);
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> service.route(10L, null)).isInstanceOf(CourseCarRouteException.class);
        }
        verify(client, times(2)).route(org.mockito.ArgumentMatchers.anyList());
        assertThat(original.days().get(0).items()).extracting(CourseDetailResponse.ItemDto::id)
                .containsExactly(1L, 2L);
    }

    @Test
    void cachesTheSameOrderedCourseAndMissesWhenOrderChanges() {
        CourseQueryService query = mock(CourseQueryService.class);
        KakaoMobilityClient client = mock(KakaoMobilityClient.class);
        CourseDetailResponse first = course(List.of(item(1, 33.24, 126.57), item(2, 33.25, 126.56)));
        CourseDetailResponse changed = course(List.of(item(2, 33.25, 126.56), item(1, 33.24, 126.57)));
        when(query.detail(10L, null)).thenReturn(first, first, changed);
        when(client.route(org.mockito.ArgumentMatchers.anyList())).thenReturn(
                new KakaoMobilityClient.Route(1000, 120,
                        List.of(new KakaoMobilityClient.RouteSection(1000, 120, List.of()))));
        CourseCarRouteService service = new CourseCarRouteService(
                query, client, Duration.ofMinutes(10), 2);

        assertThat(service.route(10L, null).cached()).isFalse();
        assertThat(service.route(10L, null).cached()).isTrue();
        assertThat(service.route(10L, null).cached()).isFalse();
        verify(client, times(2)).route(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void rejectsNonRentalCarBeforeCallingProvider() {
        CourseQueryService query = mock(CourseQueryService.class);
        KakaoMobilityClient client = mock(KakaoMobilityClient.class);
        CourseDetailResponse course = mock(CourseDetailResponse.class);
        when(course.transport()).thenReturn(Transport.PUBLIC_TRANSIT);
        when(query.detail(10L, 7L)).thenReturn(course);
        CourseCarRouteService service = new CourseCarRouteService(
                query, client, Duration.ofMinutes(10), 2);

        assertThatThrownBy(() -> service.route(10L, 7L))
                .isInstanceOf(com.example.hangat.common.exception.BaseException.class);
        verify(client, times(0)).route(org.mockito.ArgumentMatchers.anyList());
    }

    private CourseDetailResponse course(List<CourseDetailResponse.ItemDto> items) {
        CourseDetailResponse course = mock(CourseDetailResponse.class);
        when(course.id()).thenReturn(10L);
        when(course.transport()).thenReturn(Transport.RENTAL_CAR);
        when(course.accommodation()).thenReturn(null);
        when(course.days()).thenReturn(List.of(
                new CourseDetailResponse.DayDto(1, LocalDate.of(2026, 9, 3), items)));
        return course;
    }

    private CourseDetailResponse.ItemDto item(long id, double latitude, double longitude) {
        CourseDetailResponse.ItemDto item = mock(CourseDetailResponse.ItemDto.class);
        when(item.id()).thenReturn(id);
        when(item.placeId()).thenReturn(100L + id);
        when(item.placeName()).thenReturn("place-" + id);
        when(item.latitude()).thenReturn(latitude);
        when(item.longitude()).thenReturn(longitude);
        return item;
    }
}
