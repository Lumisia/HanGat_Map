package com.example.hangat.course;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.map.goodprice.KakaoLocalClient;
import com.example.hangat.map.model.entity.Place;
import com.example.hangat.map.model.entity.Region;
import com.example.hangat.map.repository.RegionRepository;
import com.example.hangat.map.service.RegionResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KakaoAccommodationProviderTest {

    private final KakaoLocalClient kakao = mock(KakaoLocalClient.class);
    private final RegionRepository regions = mock(RegionRepository.class);
    private final Region east = Region.builder().code("EAST").name("동부").displayOrder((byte) 1).build();
    private final KakaoAccommodationProvider provider =
            new KakaoAccommodationProvider(kakao, new RegionResolver(), regions);
    private Place anchor;

    @BeforeEach
    void setUp() {
        anchor = Place.builder().region(east).name("비자림").normalizedName("비자림")
                .latitude(new BigDecimal("33.49")).longitude(new BigDecimal("126.81")).build();
        when(regions.findByCode("EAST")).thenReturn(Optional.of(east));
        when(kakao.resolveAdministrativeRegion(any(), any())).thenReturn(Optional.of(
                new KakaoLocalClient.KakaoAdministrativeRegion(
                        "제주특별자치도", "제주시", "구좌읍", "50110")));
    }

    @Test
    void 실제_AD5_id만_추천하고_검증한다() {
        when(kakao.searchLodgings(any(), any(), anyInt())).thenReturn(List.of(place("real-1", "AD5")));
        assertThat(provider.recommend(List.of(anchor))).singleElement()
                .satisfies(result -> assertThat(result.place().id()).isEqualTo("real-1"));
        assertThat(provider.verify(List.of(anchor), "KAKAO_LOCAL", "real-1").region())
                .isEqualTo(east);
    }

    @Test
    void AD5가_아니거나_id가_검색범위에_없거나_MOCK이면_거부한다() {
        when(kakao.searchLodgings(any(), any(), anyInt())).thenReturn(List.of(place("food-1", "FD6")));
        assertThat(provider.recommend(List.of(anchor))).isEmpty();
        assertThatThrownBy(() -> provider.verify(List.of(anchor), "KAKAO_LOCAL", "food-1"))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> provider.verify(List.of(anchor), "KAKAO_LOCAL", "other"))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> provider.verify(List.of(anchor), "KAKAO_LOCAL", "MOCK_KAKAO_1"))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void AD5_응답이어도_요청_sourcePlaceId와_다르면_거부한다() {
        when(kakao.searchLodgings(any(), any(), anyInt()))
                .thenReturn(List.of(place("real-1", "AD5")));

        assertThatThrownBy(() -> provider.verify(
                List.of(anchor), "KAKAO_LOCAL", "different-id"))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void 공식_region을_기존_권역으로_매핑하지_못하면_저장후보가_아니다() {
        when(kakao.searchLodgings(any(), any(), anyInt())).thenReturn(List.of(place("real-1", "AD5")));
        when(kakao.resolveAdministrativeRegion(any(), any())).thenReturn(Optional.of(
                new KakaoLocalClient.KakaoAdministrativeRegion("서울특별시", "중구", "명동", "11140")));
        assertThat(provider.recommend(List.of(anchor))).isEmpty();
        assertThatThrownBy(() -> provider.verify(List.of(anchor), "KAKAO_LOCAL", "real-1"))
                .isInstanceOf(BaseException.class);
    }

    private KakaoLocalClient.KakaoPlace place(String id, String category) {
        return new KakaoLocalClient.KakaoPlace(
                id, "Kakao 원본 호텔", "Kakao 지번", "Kakao 도로명",
                new BigDecimal("33.45"), new BigDecimal("126.55"), category,
                "여행 > 숙박", "064", "https://place.map.kakao.com/" + id, 1000);
    }
}
