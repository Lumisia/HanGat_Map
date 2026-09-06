package com.example.hangat.course;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.common.model.BaseResponseStatus;
import com.example.hangat.map.goodprice.KakaoLocalClient;
import com.example.hangat.map.goodprice.KakaoLocalClient.KakaoAdministrativeRegion;
import com.example.hangat.map.goodprice.KakaoLocalClient.KakaoPlace;
import com.example.hangat.map.model.entity.Place;
import com.example.hangat.map.model.entity.Region;
import com.example.hangat.map.repository.RegionRepository;
import com.example.hangat.map.service.RegionResolver;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 저장 가능한 숙소를 Kakao REST 사실과 Course의 저장 좌표로 검증한다. */
@Service
public class KakaoAccommodationProvider {

    static final int SEARCH_RADIUS_METERS = 20_000;
    private static final int RECOMMENDATION_LIMIT = 3;
    private static final String SOURCE_CODE = "KAKAO_LOCAL";
    private static final String LODGING_CATEGORY = "AD5";

    private final KakaoLocalClient kakao;
    private final RegionResolver regionResolver;
    private final RegionRepository regionRepository;

    public KakaoAccommodationProvider(
            KakaoLocalClient kakao,
            RegionResolver regionResolver,
            RegionRepository regionRepository
    ) {
        this.kakao = kakao;
        this.regionResolver = regionResolver;
        this.regionRepository = regionRepository;
    }

    public List<VerifiedAccommodation> recommend(List<Place> coursePlaces) {
        LinkedHashMap<String, KakaoPlace> unique = searchCourseRange(coursePlaces);
        List<VerifiedAccommodation> result = new ArrayList<>();
        for (KakaoPlace place : unique.values()) {
            resolve(place).ifPresent(result::add);
            if (result.size() == RECOMMENDATION_LIMIT) break;
        }
        return List.copyOf(result);
    }

    public VerifiedAccommodation verify(
            List<Place> coursePlaces,
            String sourceCode,
            String sourcePlaceId
    ) {
        if (!SOURCE_CODE.equals(sourceCode) || sourcePlaceId == null
                || sourcePlaceId.isBlank() || sourcePlaceId.startsWith("MOCK_KAKAO_")) {
            throw invalid("검증된 Kakao 숙소 identity가 아닙니다.");
        }
        KakaoPlace place = searchCourseRange(coursePlaces).get(sourcePlaceId);
        if (place == null) {
            throw invalid("선택한 숙소가 현재 코스 검색 범위에 없습니다.");
        }
        return resolve(place).orElseThrow(() -> invalid("숙소의 공식 제주 권역을 확인할 수 없습니다."));
    }

    private LinkedHashMap<String, KakaoPlace> searchCourseRange(List<Place> coursePlaces) {
        LinkedHashMap<String, Place> anchors = new LinkedHashMap<>();
        for (Place place : coursePlaces) {
            if (place.getLatitude() != null && place.getLongitude() != null) {
                anchors.putIfAbsent(place.getRegion().getCode(), place);
            }
        }
        if (anchors.isEmpty()) {
            throw invalid("코스 장소 좌표가 없어 주변 숙소를 확인할 수 없습니다.");
        }

        LinkedHashMap<String, KakaoPlace> unique = new LinkedHashMap<>();
        for (Place anchor : anchors.values()) {
            for (KakaoPlace place : kakao.searchLodgings(
                    anchor.getLongitude(), anchor.getLatitude(), SEARCH_RADIUS_METERS)) {
                if (LODGING_CATEGORY.equals(place.categoryGroupCode())
                        && place.id() != null && !place.id().startsWith("MOCK_KAKAO_")
                        && place.distanceMeters() != null
                        && place.distanceMeters() >= 0
                        && place.distanceMeters() <= SEARCH_RADIUS_METERS) {
                    unique.putIfAbsent(place.id(), place);
                }
            }
        }
        return unique;
    }

    private java.util.Optional<VerifiedAccommodation> resolve(KakaoPlace place) {
        if (!LODGING_CATEGORY.equals(place.categoryGroupCode())) {
            return java.util.Optional.empty();
        }
        KakaoAdministrativeRegion official = kakao.resolveAdministrativeRegion(
                        place.longitude(), place.latitude())
                .orElse(null);
        if (official == null) return java.util.Optional.empty();
        String regionCode = regionResolver.resolveAdministrativeRegion(
                official.region1DepthName(), official.region2DepthName(), official.region3DepthName());
        if (regionCode == null) return java.util.Optional.empty();
        return regionRepository.findByCode(regionCode)
                .filter(Region::isActive)
                .map(region -> new VerifiedAccommodation(place, region));
    }

    private BaseException invalid(String message) {
        return new BaseException(BaseResponseStatus.REQUEST_ERROR, Map.of("accommodation", message));
    }

    public record VerifiedAccommodation(KakaoPlace place, Region region) {
    }
}
