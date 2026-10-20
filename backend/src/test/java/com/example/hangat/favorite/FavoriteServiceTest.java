package com.example.hangat.favorite;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.common.model.BaseResponseStatus;
import com.example.hangat.favorite.model.Favorite;
import com.example.hangat.favorite.model.FavoriteResponse;
import com.example.hangat.favorite.model.FavoriteStateResponse;
import com.example.hangat.favorite.repository.FavoriteRepository;
import com.example.hangat.favorite.service.FavoriteService;
import com.example.hangat.map.model.entity.Place;
import com.example.hangat.map.model.entity.PlaceCategory;
import com.example.hangat.map.model.entity.Region;
import com.example.hangat.map.model.enums.BusinessStatus;
import com.example.hangat.map.repository.CongestionForecastRepository;
import com.example.hangat.map.repository.PlaceImageRepository;
import com.example.hangat.map.repository.PlaceRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 찜 서비스 - 멱등 토글과 목록 조립. DB 없이 리포지토리를 목으로 둔다. */
class FavoriteServiceTest {

    private final FavoriteRepository favorites = mock(FavoriteRepository.class);
    private final PlaceRepository places = mock(PlaceRepository.class);
    private final PlaceImageRepository images = mock(PlaceImageRepository.class);
    private final CongestionForecastRepository forecasts = mock(CongestionForecastRepository.class);
    private final FavoriteService service = new FavoriteService(favorites, places, images, forecasts);

    @Test
    void addRejectsUnknownPlaceWith3201() {
        when(places.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.add(1L, 999L))
                .isInstanceOf(BaseException.class)
                .satisfies(e -> assertThat(((BaseException) e).getStatus()).isEqualTo(BaseResponseStatus.PLACE_NOT_FOUND));
        verify(favorites, never()).save(any());
    }

    @Test
    void addSavesOnceAndReportsFavorited() {
        Place place = place(5L);
        when(places.findById(5L)).thenReturn(Optional.of(place));
        when(favorites.existsByUserIdAndPlaceId(1L, 5L)).thenReturn(false);

        FavoriteStateResponse res = service.add(1L, 5L);

        assertThat(res.favorited()).isTrue();
        assertThat(res.placeId()).isEqualTo(5L);
        verify(favorites).save(any(Favorite.class));
    }

    @Test
    void addIsIdempotentWhenAlreadyFavorited() {
        Place place = place(5L);   // when(...) 안에서 또 스터빙하면 Mockito 가 UnfinishedStubbing 으로 막는다
        when(places.findById(5L)).thenReturn(Optional.of(place));
        when(favorites.existsByUserIdAndPlaceId(1L, 5L)).thenReturn(true);

        FavoriteStateResponse res = service.add(1L, 5L);

        assertThat(res.favorited()).isTrue();
        verify(favorites, never()).save(any());
    }

    @Test
    void removeIsIdempotentEvenWhenNothingWasDeleted() {
        when(favorites.deleteByUserIdAndPlaceId(1L, 5L)).thenReturn(0);

        FavoriteStateResponse res = service.remove(1L, 5L);

        assertThat(res.favorited()).isFalse();
        assertThat(res.placeId()).isEqualTo(5L);
    }

    @Test
    void emptyListSkipsBatchLookups() {
        when(favorites.findAllWithPlaceByUserId(1L)).thenReturn(List.of());

        assertThat(service.list(1L)).isEmpty();
        verify(places, never()).findApiTagNamesOf(anyList());
        verify(forecasts, never()).findLatestBaseAt();
    }

    @Test
    void listAttachesTagImageAndTodayCrowd() {
        Place place = place(5L);
        Favorite favorite = Favorite.builder().userId(1L).place(place).build();
        LocalDateTime base = LocalDateTime.of(2026, 9, 7, 0, 0);
        when(favorites.findAllWithPlaceByUserId(1L)).thenReturn(List.of(favorite));
        when(places.findApiTagNamesOf(List.of(5L))).thenReturn(List.<Object[]>of(new Object[]{5L, "오름"}));
        when(images.findFirstImageOf(List.of(5L))).thenReturn(List.<Object[]>of(new Object[]{5L, "thumb.jpg", "orig.jpg"}));
        when(forecasts.findLatestBaseAt()).thenReturn(Optional.of(base));
        when(forecasts.findRatesOn(eq(base), any(LocalDateTime.class), eq(List.of(5L))))
                .thenReturn(List.<Object[]>of(new Object[]{5L, new BigDecimal("37.50")}));

        List<FavoriteResponse> out = service.list(1L);

        assertThat(out).hasSize(1);
        FavoriteResponse r = out.get(0);
        assertThat(r.getPlaceId()).isEqualTo(5L);
        assertThat(r.getName()).isEqualTo("금오름");
        assertThat(r.getRegionName()).isEqualTo("서부");
        assertThat(r.getCategoryCode()).isEqualTo("TOURIST");
        assertThat(r.getTagName()).isEqualTo("오름");
        assertThat(r.getImageUrl()).isEqualTo("thumb.jpg");
        assertThat(r.getCrowdRate()).isEqualByComparingTo("37.50");
        // 별점 후기 0건은 0.00 이 아니라 null - 상세와 같은 규칙
        assertThat(r.getRatingAvg()).isNull();
        assertThat(r.isFree()).isFalse();
    }

    @Test
    void listLeavesCrowdNullWhenNoForecastVersionExists() {
        Favorite favorite = Favorite.builder().userId(1L).place(place(5L)).build();
        when(favorites.findAllWithPlaceByUserId(1L)).thenReturn(List.of(favorite));
        when(places.findApiTagNamesOf(anyList())).thenReturn(List.of());
        when(images.findFirstImageOf(anyList())).thenReturn(List.of());
        when(forecasts.findLatestBaseAt()).thenReturn(Optional.empty());

        FavoriteResponse r = service.list(1L).get(0);

        assertThat(r.getCrowdRate()).isNull();
        assertThat(r.getTagName()).isNull();
        assertThat(r.getImageUrl()).isNull();
        verify(forecasts, never()).findRatesOn(any(), any(), anyList());
    }

    /** 엔티티는 빌더가 private/protected 라 목으로 만든다 - 응답 변환에 쓰는 getter 만 채운다. */
    private static Place place(Long id) {
        Region region = mock(Region.class);
        when(region.getCode()).thenReturn("WEST");
        when(region.getName()).thenReturn("서부");
        PlaceCategory category = mock(PlaceCategory.class);
        when(category.getCode()).thenReturn("TOURIST");
        when(category.getName()).thenReturn("관광지");
        Place place = mock(Place.class);
        when(place.getId()).thenReturn(id);
        when(place.getName()).thenReturn("금오름");
        when(place.getRegion()).thenReturn(region);
        when(place.getPrimaryCategory()).thenReturn(category);
        when(place.getBusinessStatus()).thenReturn(BusinessStatus.UNKNOWN);
        when(place.getRatingAvg()).thenReturn(new BigDecimal("0.00"));
        when(place.getReviewCount()).thenReturn(0);
        return place;
    }
}
