package com.example.hangat.map.place;

import com.example.hangat.map.model.entity.DataSource;
import com.example.hangat.map.model.entity.Place;
import com.example.hangat.map.model.entity.PlaceCategory;
import com.example.hangat.map.model.entity.PlaceSourceMapping;
import com.example.hangat.map.model.entity.PlaceTag;
import com.example.hangat.map.model.entity.Region;
import com.example.hangat.map.model.entity.Tag;
import com.example.hangat.map.model.enums.TagSourceType;
import com.example.hangat.map.repository.DataSourceRepository;
import com.example.hangat.map.repository.PlaceCategoryRepository;
import com.example.hangat.map.repository.PlaceRepository;
import com.example.hangat.map.repository.PlaceSourceMappingRepository;
import com.example.hangat.map.repository.PlaceTagRepository;
import com.example.hangat.map.repository.RegionRepository;
import com.example.hangat.map.repository.TagRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 적재 청크 하나를 저장하는 전담 컴포넌트.
 *
 * <p><b>왜 별도 클래스인가</b>: 청크마다 트랜잭션을 끊기 위해서다.
 * 2,147건을 한 트랜잭션으로 묶으면 마지막 1건이 실패할 때 전부 롤백된다.
 * 같은 클래스 안에서 {@code @Transactional} 메서드를 호출하면 프록시를 안 거쳐 적용되지 않으므로
 * 호출하는 쪽({@link PlaceIngestService})과 분리했다.
 *
 * <p>중간에 실패해도 앞 청크는 남고, 다시 돌리면 이어서 채운다 -
 * 중복은 {@code UNIQUE(source_code, source_place_id)}가 막는다.
 */
@Component
public class PlaceIngestWriter {

    private static final Logger log = LoggerFactory.getLogger(PlaceIngestWriter.class);

    private final PlaceRepository placeRepository;
    private final PlaceSourceMappingRepository mappingRepository;
    private final RegionRepository regionRepository;
    private final PlaceCategoryRepository categoryRepository;
    private final DataSourceRepository dataSourceRepository;
    private final TagRepository tagRepository;
    private final PlaceTagRepository placeTagRepository;

    public PlaceIngestWriter(PlaceRepository placeRepository,
                             PlaceSourceMappingRepository mappingRepository,
                             RegionRepository regionRepository,
                             PlaceCategoryRepository categoryRepository,
                             DataSourceRepository dataSourceRepository,
                             TagRepository tagRepository,
                             PlaceTagRepository placeTagRepository) {
        this.placeRepository = placeRepository;
        this.mappingRepository = mappingRepository;
        this.regionRepository = regionRepository;
        this.categoryRepository = categoryRepository;
        this.dataSourceRepository = dataSourceRepository;
        this.tagRepository = tagRepository;
        this.placeTagRepository = placeTagRepository;
    }

    /** 저장 대상 한 건. 파싱·판정이 끝난 상태로 넘어온다. */
    public record Row(String sourcePlaceId, String regionCode, String categoryCode, String tagCode,
                      String name, String normalizedName, String roadAddress,
                      BigDecimal latitude, BigDecimal longitude, String phone, String imageUrl,
                      String dataHash, String rawPayload, LocalDateTime sourceUpdatedAt) {
    }

    /** 청크 처리 결과. */
    public record ChunkResult(int inserted, int updated, int unchanged, int tagged) {
    }

    @Transactional
    public ChunkResult saveChunk(String sourceCode, Iterable<Row> rows) {
        DataSource source = dataSourceRepository.findById(sourceCode)
                .orElseThrow(() -> new IllegalStateException(
                        "data_sources에 '" + sourceCode + "' 행이 없다. data.sql 적재를 먼저 확인할 것"));

        // 마스터는 4행·7행·246행뿐이라 트랜잭션 안에서 한 번만 읽어 재사용한다
        Map<String, Region> regions = new HashMap<>();
        Map<String, PlaceCategory> categories = new HashMap<>();
        Map<String, Tag> tags = new HashMap<>();

        int inserted = 0;
        int updated = 0;
        int unchanged = 0;
        int tagged = 0;

        for (Row row : rows) {
            Region region = regions.computeIfAbsent(row.regionCode(),
                    code -> regionRepository.findByCode(code)
                            .orElseThrow(() -> new IllegalStateException("regions에 '" + code + "' 없음")));
            PlaceCategory category = categories.computeIfAbsent(row.categoryCode(),
                    code -> categoryRepository.findByCode(code)
                            .orElseThrow(() -> new IllegalStateException("place_categories에 '" + code + "' 없음")));

            Optional<PlaceSourceMapping> found =
                    mappingRepository.findBySourceCodeAndSourcePlaceId(sourceCode, row.sourcePlaceId());

            if (found.isEmpty()) {
                Place place = placeRepository.save(Place.builder()
                        .region(region)
                        .primaryCategory(category)
                        .name(row.name())
                        .normalizedName(row.normalizedName())
                        .roadAddress(row.roadAddress())
                        .latitude(row.latitude())
                        .longitude(row.longitude())
                        .phone(row.phone())
                        .imageUrl(row.imageUrl())
                        .isGoodPrice(false)
                        .isHiddenGem(false)
                        .reviewCount(0)
                        .build());

                if (applyTag(place, row, tags, false)) {
                    tagged++;
                }

                mappingRepository.save(PlaceSourceMapping.builder()
                        .place(place)
                        .source(source)
                        .sourcePlaceId(row.sourcePlaceId())
                        .dataHash(row.dataHash())
                        .rawPayload(row.rawPayload())
                        .sourceUpdatedAt(row.sourceUpdatedAt())
                        .lastSyncedAt(LocalDateTime.now())
                        .build());
                inserted++;
                continue;
            }

            PlaceSourceMapping mapping = found.get();
            // 원본이 그대로면 UPDATE를 건너뛴다 - 2,147건을 매번 쓰지 않는다
            if (row.dataHash() != null && row.dataHash().equals(mapping.getDataHash())) {
                unchanged++;
                continue;
            }

            mapping.getPlace().updateFromSource(region, category, row.name(), row.normalizedName(),
                    row.roadAddress(), row.latitude(), row.longitude(), row.phone(), row.imageUrl());
            mapping.markSynced(row.dataHash(), row.rawPayload(), row.sourceUpdatedAt());
            if (applyTag(mapping.getPlace(), row, tags, true)) {
                tagged++;
            }
            updated++;
        }

        return new ChunkResult(inserted, updated, unchanged, tagged);
    }

    /**
     * 장소에 세부분류 태그를 붙인다. KTO는 장소당 소분류 하나를 주므로 항상 1건이다.
     *
     * @param replaceExisting 갱신 경로면 true - 분류가 바뀌었을 수 있어 기존 API 태그를 걷어내고
     *                        다시 붙인다. 신규 경로에서는 붙어 있을 게 없으므로 DELETE를 아낀다
     * @return 실제로 붙였으면 true
     */
    private boolean applyTag(Place place, Row row, Map<String, Tag> cache, boolean replaceExisting) {
        if (replaceExisting) {
            placeTagRepository.deleteByPlaceAndSourceType(place, TagSourceType.API);
        }
        if (row.tagCode() == null) {
            return false;
        }
        Tag tag = cache.computeIfAbsent(row.tagCode(),
                code -> tagRepository.findByCode(code).orElse(null));
        if (tag == null) {
            // 코드표에 없는 분류코드. KTO가 코드표보다 먼저 장소를 푸는 경우가 있어 장소는 살린다
            log.warn("코드표에 없는 세부분류 code={} title={}", row.tagCode(), row.name());
            return false;
        }
        placeTagRepository.save(PlaceTag.fromApi(place, tag));
        return true;
    }
}
