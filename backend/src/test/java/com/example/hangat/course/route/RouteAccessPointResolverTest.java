package com.example.hangat.course.route;

import com.example.hangat.map.goodprice.KakaoLocalClient;
import com.example.hangat.map.goodprice.KakaoLocalClient.KakaoPlace;
import com.example.hangat.map.model.entity.Place;
import com.example.hangat.map.model.entity.Region;
import com.example.hangat.map.repository.PlaceRepository;
import com.example.hangat.map.service.RegionResolver;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RouteAccessPointResolverTest {
    @Test
    void trustedParkingBeyondOneKmRequiresExactIdentityAndSameOfficialRegion() {
        var kakao = mock(KakaoLocalClient.class);
        var places = mock(PlaceRepository.class);
        var regions = mock(RegionResolver.class);
        var mappings = mock(com.example.hangat.map.repository.PlaceSourceMappingRepository.class);
        var mapping = mock(com.example.hangat.map.model.entity.PlaceSourceMapping.class);
        var place = mock(Place.class);
        var region = mock(Region.class);
        when(places.findDetailById(1L)).thenReturn(Optional.of(place));
        when(place.getId()).thenReturn(1L);
        when(place.getName()).thenReturn("어승생");
        when(place.getRegion()).thenReturn(region);
        when(region.getCode()).thenReturn("NORTH");
        when(place.getLatitude()).thenReturn(new BigDecimal("33.3856593715"));
        when(place.getLongitude()).thenReturn(new BigDecimal("126.5067521811"));
        when(mappings.findByPlaceIdAndSourceCode(1L, "KTO")).thenReturn(Optional.of(mapping));
        when(mapping.isActive()).thenReturn(true);
        when(mapping.getSourcePlaceId()).thenReturn("127202");
        var parking = new KakaoPlace("20588448", "어리목주차장", "제주시 해안동 산 220-13",
                "제주특별자치도 제주시 1100로 2070-61", new BigDecimal("33.3920786624679"),
                new BigDecimal("126.49461170461"), "PK6", "주차장", null, null, 1335);
        when(kakao.searchRouteAccessPoints(eq("어리목주차장"), any(), any(), eq(2000)))
                .thenReturn(List.of(parking));
        when(kakao.resolveAdministrativeRegion(any(), any())).thenReturn(Optional.of(
                new KakaoLocalClient.KakaoAdministrativeRegion("제주특별자치도", "제주시", "해안동", "code")));
        when(regions.resolveAdministrativeRegion(any(), any(), any())).thenReturn("NORTH", "SOUTH");
        var resolver = new RouteAccessPointResolver(kakao, places, regions, mappings);
        var result = resolver.resolve(1L).orElseThrow();
        assertThat(result.sourcePlaceId()).isEqualTo("20588448");
        assertThat(result.straightDistanceMeters()).isBetween(1300L, 1400L);
        assertThat(result.notice()).contains("어승생악 인근 어리목주차장까지 차량 경로");
        assertThat(resolver.resolve(1L)).isEmpty();
        verify(places, never()).save(any());
        verify(mappings, never()).save(any());
        assertThat(place.getLatitude()).isEqualTo(new BigDecimal("33.3856593715"));
    }
    KakaoPlace parking(String id, String name, double lat) {
        return new KakaoPlace(id,name,"주소","도로주소",BigDecimal.valueOf(lat),BigDecimal.valueOf(126.5),
                "PK6","주차장",null,null,100);
    }
    @Test
    void rejectsUnrelatedMockOutOfRangeAndMissingCoordinates() {
        assertThat(RouteAccessPointResolver.associated(parking("1","다른 주차장",33.401),"어승생",33.4,126.5)).isFalse();
        assertThat(RouteAccessPointResolver.associated(parking("MOCK_1","어승생 주차장",33.401),"어승생",33.4,126.5)).isFalse();
        assertThat(RouteAccessPointResolver.associated(parking("1","어승생 주차장",33.5),"어승생",33.4,126.5)).isFalse();
        assertThat(RouteAccessPointResolver.associated(parking("1","어승생 주차장",33.401),"어승생",33.4,126.5)).isTrue();
    }
    @Test
    void officialRegionMustMatchAndOriginalPlaceIsReadOnly() {
        var kakao=mock(KakaoLocalClient.class); var places=mock(PlaceRepository.class); var regions=mock(RegionResolver.class);
        var place=mock(Place.class); var region=mock(Region.class);
        when(places.findDetailById(1L)).thenReturn(Optional.of(place));
        when(place.getId()).thenReturn(1L); when(place.getName()).thenReturn("어승생");
        when(place.getRegion()).thenReturn(region); when(region.getCode()).thenReturn("NORTH");
        when(place.getLatitude()).thenReturn(BigDecimal.valueOf(33.4)); when(place.getLongitude()).thenReturn(BigDecimal.valueOf(126.5));
        when(kakao.searchRouteAccessPoints(any(),any(),any(),eq(1000))).thenReturn(List.of(parking("77","어승생 주차장",33.401)));
        when(kakao.resolveAdministrativeRegion(any(),any())).thenReturn(Optional.of(
                new KakaoLocalClient.KakaoAdministrativeRegion("제주특별자치도","제주시","오라동","code")));
        when(regions.resolveAdministrativeRegion(any(),any(),any())).thenReturn("SOUTH","NORTH");
        var resolver=new RouteAccessPointResolver(kakao,places,regions);
        assertThat(resolver.resolve(1L)).isEmpty();
        var result=resolver.resolve(1L).orElseThrow();
        assertThat(result.sourcePlaceId()).isEqualTo("77");
        assertThat(result.type()).isEqualTo("PARKING");
        assertThat(result.straightDistanceMeters()).isBetween(110L,112L);
        verify(kakao,times(2)).searchRouteAccessPoints(any(),any(),any(),eq(1000));
        verify(places,never()).save(any());
        assertThat(place.getLatitude()).isEqualTo(BigDecimal.valueOf(33.4));
    }
}
