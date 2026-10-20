package com.example.hangat.course.transit;
import com.example.hangat.course.model.*;
import com.example.hangat.course.model.enums.Transport;
import com.example.hangat.course.service.CourseQueryService;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
class CourseTransitRouteServiceTest {
    CourseDetailResponse course() {
        var c=mock(CourseDetailResponse.class);when(c.id()).thenReturn(15L);when(c.transport()).thenReturn(Transport.PUBLIC_TRANSIT);
        var items=new ArrayList<CourseDetailResponse.ItemDto>();
        for(int n=1;n<=3;n++){var i=mock(CourseDetailResponse.ItemDto.class);when(i.id()).thenReturn((long)n);when(i.position()).thenReturn(n);when(i.placeName()).thenReturn("장소"+n);when(i.latitude()).thenReturn(33.4);when(i.longitude()).thenReturn(126.5+n*.01);items.add(i);}
        when(c.days()).thenReturn(List.of(new CourseDetailResponse.DayDto(1,LocalDate.of(2026,9,8),items)));return c;
    }
    CourseDetailResponse course(int itemCount,long id) {
        var c=course();when(c.id()).thenReturn(id);var items=new ArrayList<CourseDetailResponse.ItemDto>();
        for(int n=1;n<=itemCount;n++){var i=mock(CourseDetailResponse.ItemDto.class);when(i.id()).thenReturn((long)n);when(i.position()).thenReturn(n);when(i.placeName()).thenReturn("장소"+n);when(i.latitude()).thenReturn(33.4);when(i.longitude()).thenReturn(126.5+n*.01);items.add(i);}
        when(c.days()).thenReturn(List.of(new CourseDetailResponse.DayDto(1,LocalDate.of(2026,9,8),items)));return c;
    }
    final KakaoTransitClient.Result ok=new KakaoTransitClient.Result("OK",1000L,1200L,1,null,List.of());
    @Test void hotelAndNoHotelPreserveOrderAndPartialNeverProducesCompleteTotal() {
        var c=course();var q=mock(CourseQueryService.class);when(q.detail(15L,null)).thenReturn(c);var client=mock(KakaoTransitClient.class);
        when(client.route(any(),any(),any())).thenReturn(ok,KakaoTransitClient.Result.failure("NO_RESULTS"));
        var service=new CourseTransitRouteService(q,client);var r=service.route(15L,null);
        assertThat(r.days().get(0).legs()).hasSize(2);assertThat(r.days().get(0).durationSeconds()).isNull();
        assertThat(r.days().get(0).legs().get(0).durationSeconds()).isEqualTo(1200);
        assertThat(r.days().get(0).legs().get(1).durationSeconds()).isNull();
        var hotel=mock(AccommodationDto.class);when(hotel.getPlaceName()).thenReturn("숙소");when(hotel.getLatitude()).thenReturn(33.4);when(hotel.getLongitude()).thenReturn(126.5);when(c.accommodation()).thenReturn(hotel);
        var p=CourseTransitRouteService.points(c,c.days().get(0));assertThat(p).hasSize(5);assertThat(p.get(0)).isEqualTo(p.get(4));
        assertThat(p.subList(1,4).stream().map(x->x.id())).containsExactly("ITEM:1","ITEM:2","ITEM:3");
    }
    @Test void cacheStillChecksPermissionsAndScheduleChangeInvalidates() {
        var c=course();var q=mock(CourseQueryService.class);when(q.detail(15L,null)).thenReturn(c);var client=mock(KakaoTransitClient.class);when(client.route(any(),any(),any())).thenReturn(ok);
        var s=new CourseTransitRouteService(q,client);assertThat(s.route(15L,null).cached()).isFalse();assertThat(s.route(15L,null).cached()).isTrue();verify(client,times(2)).route(any(),any(),any());
        when(c.days().get(0).items().get(0).longitude()).thenReturn(126.9);assertThat(s.route(15L,null).cached()).isFalse();verify(client,times(3)).route(any(),any(),any());
        when(q.detail(15L,99L)).thenThrow(new IllegalStateException("forbidden"));assertThatThrownBy(()->s.route(15L,99L)).isInstanceOf(IllegalStateException.class);verify(client,times(3)).route(any(),any(),any());
    }
    @Test void accommodationSelectionAndReplacementUseDifferentCacheKeys() {
        var c=course();var q=mock(CourseQueryService.class);when(q.detail(15L,null)).thenReturn(c);
        var client=mock(KakaoTransitClient.class);when(client.route(any(),any(),any())).thenReturn(ok);
        var service=new CourseTransitRouteService(q,client);

        assertThat(service.route(15L,null).cached()).isFalse();
        assertThat(service.route(15L,null).cached()).isTrue();
        verify(client,times(2)).route(any(),any(),any());

        var hotel=mock(AccommodationDto.class);when(hotel.getPlaceName()).thenReturn("숙소 A");
        when(hotel.getLatitude()).thenReturn(33.41);when(hotel.getLongitude()).thenReturn(126.51);
        when(c.accommodation()).thenReturn(hotel);
        assertThat(service.route(15L,null).cached()).isFalse();
        assertThat(service.route(15L,null).cached()).isTrue();
        verify(client,times(4)).route(any(),any(),any());

        when(hotel.getPlaceName()).thenReturn("숙소 B");
        when(hotel.getLatitude()).thenReturn(33.42);when(hotel.getLongitude()).thenReturn(126.52);
        assertThat(service.route(15L,null).cached()).isFalse();
        verify(client,times(6)).route(any(),any(),any());
    }
    @Test void partialRetryReusesElevenSuccessfulLegsAndRetriesOnlyFailure() {
        var c=course(13,15L);var q=mock(CourseQueryService.class);when(q.detail(15L,null)).thenReturn(c);
        var client=mock(KakaoTransitClient.class);
        when(client.route(any(),any(),any())).thenAnswer(x->{var b=x.<KakaoTransitClient.Budget>getArgument(2);b.attempts++;
            return x.<TransitRouteResponse.Stop>getArgument(1).id().equals("ITEM:13")?KakaoTransitClient.Result.failure("NO_RESULTS"):ok;});
        var service=new CourseTransitRouteService(q,client);

        var first=service.route(15L,null);var second=service.route(15L,null);
        assertThat(first.providerAttempts()).isEqualTo(12);
        assertThat(second.providerAttempts()).isEqualTo(1);
        assertThat(second.cached()).isFalse();
        assertThat(second.days().get(0).legs()).filteredOn(l->l.status().equals("OK")).hasSize(11);
        assertThat(second.days().get(0).legs()).filteredOn(l->l.status().equals("NO_RESULTS")).hasSize(1);
        verify(client,times(13)).route(any(),any(),any());
    }
    @Test void expiredDirectionAndCoordinateChangesCannotReuseLegCache() {
        var c=course(2,15L);var q=mock(CourseQueryService.class);when(q.detail(15L,null)).thenReturn(c);
        var client=mock(KakaoTransitClient.class);when(client.route(any(),any(),any())).thenReturn(ok);
        var clock=new MutableClock(Instant.parse("2026-09-08T00:00:00Z"));var service=new CourseTransitRouteService(q,client,clock);
        assertThat(service.route(15L,null).cached()).isFalse();assertThat(service.route(15L,null).cached()).isTrue();verify(client,times(1)).route(any(),any(),any());
        clock.advance(Duration.ofSeconds(60));assertThat(service.route(15L,null).cached()).isFalse();verify(client,times(2)).route(any(),any(),any());
        var first=c.days().get(0).items().get(0);var second=c.days().get(0).items().get(1);
        when(first.longitude()).thenReturn(126.52);when(second.longitude()).thenReturn(126.51);
        assertThat(service.route(15L,null).cached()).isFalse();verify(client,times(3)).route(any(),any(),any());
        when(second.longitude()).thenReturn(126.53);assertThat(service.route(15L,null).cached()).isFalse();verify(client,times(4)).route(any(),any(),any());
    }
    @Test void concurrentSameLegAcrossCoursesCallsProviderOnce()throws Exception {
        var a=course(2,15L);var b=course(2,16L);var q=mock(CourseQueryService.class);when(q.detail(15L,null)).thenReturn(a);when(q.detail(16L,null)).thenReturn(b);
        var client=mock(KakaoTransitClient.class);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(client.route(any(),any(),any())).thenAnswer(x->{entered.countDown();release.await(3,TimeUnit.SECONDS);return ok;});
        var service=new CourseTransitRouteService(q,client);var pool=Executors.newFixedThreadPool(2);
        try{var first=pool.submit(()->service.route(15L,null));assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();var second=pool.submit(()->service.route(16L,null));release.countDown();first.get();second.get();verify(client,times(1)).route(any(),any(),any());}
        finally{release.countDown();pool.shutdownNow();}
    }
    @Test void failedLegIsNeverReturnedFromSuccessCache() {
        var c=course(2,15L);var q=mock(CourseQueryService.class);when(q.detail(15L,null)).thenReturn(c);
        var client=mock(KakaoTransitClient.class);when(client.route(any(),any(),any())).thenReturn(KakaoTransitClient.Result.failure("NO_RESULTS"),ok);
        var service=new CourseTransitRouteService(q,client);
        assertThat(service.route(15L,null).days().get(0).legs().get(0).status()).isEqualTo("NO_RESULTS");
        assertThat(service.route(15L,null).days().get(0).legs().get(0).status()).isEqualTo("OK");
        assertThat(service.route(15L,null).cached()).isTrue();verify(client,times(2)).route(any(),any(),any());
    }
    @Test void simultaneousSameRequestCallsProviderOnlyOncePerLeg()throws Exception {
        var c=course();var q=mock(CourseQueryService.class);when(q.detail(15L,null)).thenReturn(c);var client=mock(KakaoTransitClient.class);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(client.route(any(),any(),any())).thenAnswer(x->{entered.countDown();release.await(3,TimeUnit.SECONDS);return ok;});
        var service=new CourseTransitRouteService(q,client);var pool=Executors.newFixedThreadPool(2);
        try{var a=pool.submit(()->service.route(15L,null));assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();var b=pool.submit(()->service.route(15L,null));release.countDown();assertThat(a.get().days()).hasSize(1);assertThat(b.get().days()).hasSize(1);verify(client,times(2)).route(any(),any(),any());}finally{release.countDown();pool.shutdownNow();}
    }
    static final class MutableClock extends Clock {
        private Instant now;MutableClock(Instant now){this.now=now;}void advance(Duration duration){now=now.plus(duration);}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}@Override public Clock withZone(ZoneId zone){return this;}@Override public Instant instant(){return now;}
    }
}
