package com.example.hangat.course;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.common.model.BaseResponseStatus;
import com.example.hangat.course.facts.CandidateIdentity;
import com.example.hangat.course.facts.CourseCandidate;
import com.example.hangat.course.facts.PlaceFact;
import com.example.hangat.course.model.AccommodationDto;
import com.example.hangat.course.KakaoAccommodationProvider.VerifiedAccommodation;
import com.example.hangat.map.model.entity.DataSource;
import com.example.hangat.map.model.entity.Place;
import com.example.hangat.map.model.entity.PlaceCategory;
import com.example.hangat.map.model.entity.PlaceSourceMapping;
import com.example.hangat.map.model.entity.Region;
import com.example.hangat.map.repository.DataSourceRepository;
import com.example.hangat.map.repository.PlaceCategoryRepository;
import com.example.hangat.map.repository.PlaceRepository;
import com.example.hangat.map.repository.PlaceSourceMappingRepository;
import com.example.hangat.map.repository.RegionRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

/** 코스 후보와 선택 숙소가 공유하는 외부 장소 identity 해석 경계. */
@Service
public class CoursePlaceResolver {

    private static final String LODGING_CATEGORY_CODE = "LODGING";

    private final PlaceRepository placeRepository;
    private final PlaceSourceMappingRepository mappingRepository;
    private final DataSourceRepository dataSourceRepository;
    private final RegionRepository regionRepository;
    private final PlaceCategoryRepository placeCategoryRepository;

    public CoursePlaceResolver(
            PlaceRepository placeRepository,
            PlaceSourceMappingRepository mappingRepository,
            DataSourceRepository dataSourceRepository,
            RegionRepository regionRepository,
            PlaceCategoryRepository placeCategoryRepository
    ) {
        this.placeRepository = placeRepository;
        this.mappingRepository = mappingRepository;
        this.dataSourceRepository = dataSourceRepository;
        this.regionRepository = regionRepository;
        this.placeCategoryRepository = placeCategoryRepository;
    }

    public Place resolvePlace(CourseCandidate candidate) {
        CandidateIdentity identity = candidate.identity();
        if (identity.placeId() != null) {
            Place place = placeRepository.findById(identity.placeId())
                    .orElseThrow(() -> new IllegalStateException(
                            "내부 장소를 찾을 수 없습니다: " + identity.placeId()));
            if (hasSourceIdentity(identity)) {
                ensureSourceMapping(place, identity);
            }
            return place;
        }

        String sourceCode = normalizedSourceCode(identity.sourceCode());
        String sourcePlaceId = required(identity.sourcePlaceId(), "sourcePlaceId");
        return mappingRepository.findBySourceCodeAndSourcePlaceId(sourceCode, sourcePlaceId)
                .map(PlaceSourceMapping::getPlace)
                .orElseGet(() -> createMappedPlace(candidate, sourceCode, sourcePlaceId));
    }

    public void validateCandidateReferences(CourseCandidate candidate) {
        if (candidate.identity().placeId() != null) {
            return;
        }
        resolveRegion(candidate.regionCode());
        resolveCategory(candidate.internalPlaceCategory().code());
        resolveDataSource(normalizedSourceCode(candidate.identity().sourceCode()));
    }

    /** Backend Kakao 검증을 통과한 사실만 Place/Mapping으로 투영한다. */
    public PlaceSourceMapping resolveVerifiedAccommodation(VerifiedAccommodation verified) {
        var fact = verified.place();
        return mappingRepository.findBySourceCodeAndSourcePlaceId("KAKAO_LOCAL", fact.id())
                .map(mapping -> {
                    updateAccommodationPlace(mapping.getPlace(), verified);
                    return mapping;
                })
                .orElseGet(() -> createAccommodationMapping(verified));
    }

    private PlaceSourceMapping createAccommodationMapping(VerifiedAccommodation verified) {
        var fact = verified.place();
        PlaceCategory category = resolveCategoryForRequest(LODGING_CATEGORY_CODE);
        DataSource source = resolveDataSourceForRequest("KAKAO_LOCAL");
        Place place = placeRepository.save(Place.builder()
                .region(verified.region())
                .primaryCategory(category)
                .name(fact.name()).normalizedName(normalizeName(fact.name()))
                .roadAddress(fact.roadAddress()).lotAddress(fact.address())
                .latitude(fact.latitude()).longitude(fact.longitude())
                .phone(fact.phone())
                .build());
        return mappingRepository.save(newSourceMapping(place, source, fact.id()));
    }

    private void updateAccommodationPlace(Place place, VerifiedAccommodation verified) {
        var fact = verified.place();
        place.updateVerifiedAccommodation(
                verified.region(), resolveCategoryForRequest(LODGING_CATEGORY_CODE),
                fact.name(), normalizeName(fact.name()), fact.roadAddress(), fact.address(),
                fact.latitude(), fact.longitude(), fact.phone());
    }

    private Place createMappedPlace(
            CourseCandidate candidate,
            String sourceCode,
            String sourcePlaceId
    ) {
        Region region = resolveRegion(candidate.regionCode());
        PlaceCategory category = resolveCategory(candidate.internalPlaceCategory().code());
        PlaceFact fact = candidate.place();
        Place place = placeRepository.save(Place.builder()
                .region(region)
                .primaryCategory(category)
                .name(fact.name())
                .normalizedName(normalizeName(fact.name()))
                .roadAddress(fact.roadAddress())
                .lotAddress(fact.address())
                .latitude(fact.latitude())
                .longitude(fact.longitude())
                .imageUrl(fact.imageUrl())
                .build());
        mappingRepository.save(newSourceMapping(
                place, resolveDataSource(sourceCode), sourcePlaceId));
        return place;
    }

    private void ensureSourceMapping(Place place, CandidateIdentity identity) {
        ensureSourceMapping(
                place,
                normalizedSourceCode(identity.sourceCode()),
                required(identity.sourcePlaceId(), "sourcePlaceId"));
    }

    private PlaceSourceMapping ensureSourceMapping(
            Place place,
            String sourceCode,
            String sourcePlaceId
    ) {
        return mappingRepository.findBySourceCodeAndSourcePlaceId(sourceCode, sourcePlaceId)
                .map(mapping -> {
                    if (!mapping.getPlace().getId().equals(place.getId())) {
                        throw new IllegalStateException(
                                "내부 장소와 외부 장소 매핑이 서로 충돌합니다: "
                                        + sourceCode + "/" + sourcePlaceId);
                    }
                    return mapping;
                })
                .orElseGet(() -> mappingRepository.save(newSourceMapping(
                        place, resolveDataSourceForRequest(sourceCode), sourcePlaceId)));
    }

    private PlaceSourceMapping newSourceMapping(
            Place place,
            DataSource source,
            String sourcePlaceId
    ) {
        return PlaceSourceMapping.builder()
                .place(place)
                .source(source)
                .sourcePlaceId(sourcePlaceId)
                .isActive(true)
                .build();
    }

    private Region resolveRegion(String code) {
        return regionRepository.findByCode(code)
                .filter(Region::isActive)
                .orElseThrow(() -> new IllegalStateException(
                        "등록된 권역 코드를 찾을 수 없습니다: " + code));
    }

    private PlaceCategory resolveCategory(String code) {
        return placeCategoryRepository.findByCode(code)
                .filter(PlaceCategory::isActive)
                .orElseThrow(() -> new IllegalStateException(
                        "등록된 장소 카테고리를 찾을 수 없습니다: " + code));
    }

    private PlaceCategory resolveCategoryForRequest(String code) {
        return placeCategoryRepository.findByCode(code)
                .filter(PlaceCategory::isActive)
                .orElseThrow(() -> invalid("숙소 카테고리를 확인할 수 없습니다."));
    }

    private DataSource resolveDataSource(String sourceCode) {
        return dataSourceRepository.findById(sourceCode)
                .filter(DataSource::isActive)
                .orElseThrow(() -> new IllegalStateException(
                        "등록된 데이터 출처를 찾을 수 없습니다: " + sourceCode));
    }

    private DataSource resolveDataSourceForRequest(String sourceCode) {
        return dataSourceRepository.findById(sourceCode)
                .filter(DataSource::isActive)
                .orElseThrow(() -> invalid("숙소 데이터 출처를 확인할 수 없습니다."));
    }

    private boolean hasSourceIdentity(CandidateIdentity identity) {
        return identity.sourceCode() != null && !identity.sourceCode().isBlank()
                && identity.sourcePlaceId() != null && !identity.sourcePlaceId().isBlank();
    }

    private String normalizedSourceCode(String sourceCode) {
        return required(sourceCode, "sourceCode").toUpperCase(Locale.ROOT);
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(field + "가 필요합니다.");
        }
        return value.trim();
    }

    private String normalizeName(String value) {
        return value == null
                ? ""
                : value.replaceAll("[\\s\\p{P}\\p{S}]+", "")
                        .toLowerCase(Locale.ROOT);
    }

    private BaseException invalid(String message) {
        return new BaseException(
                BaseResponseStatus.REQUEST_ERROR,
                Map.of("accommodation", message));
    }
}
