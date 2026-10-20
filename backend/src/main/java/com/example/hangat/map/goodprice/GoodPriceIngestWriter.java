package com.example.hangat.map.goodprice;

import com.example.hangat.map.goodprice.GoodPriceCsv.Row;
import com.example.hangat.map.goodprice.KakaoLocalClient.GeoPoint;
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
import com.example.hangat.map.service.PlaceNameNormalizer;
import com.example.hangat.map.service.RegionResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 착한가격 한 건 저장. 기준일은 CSV 발행일 - {@code hangat.goodprice.base-date}(CSV 를 교체할 때 같이 바꾼다).
 * 재실행 시 내용이 바뀐 업소는 갱신(REFRESHED), CSV 에서 빠진 업소는 {@link #clearMissing} 으로 해제한다.
 */
@Component
public class GoodPriceIngestWriter {

    static final String SOURCE = "MOIS_GOODPRICE";
    private final LocalDate baseDate;

    private final PlaceRepository placeRepository;
    private final PlaceSourceMappingRepository mappingRepository;
    private final DataSourceRepository dataSourceRepository;
    private final RegionRepository regionRepository;
    private final PlaceCategoryRepository categoryRepository;
    private final RegionResolver regionResolver;

    public GoodPriceIngestWriter(PlaceRepository placeRepository,
                                 PlaceSourceMappingRepository mappingRepository,
                                 DataSourceRepository dataSourceRepository,
                                 RegionRepository regionRepository,
                                 PlaceCategoryRepository categoryRepository,
                                 RegionResolver regionResolver,
                                 @Value("${hangat.goodprice.base-date:2026-06-30}") String baseDate) {
        this.placeRepository = placeRepository;
        this.mappingRepository = mappingRepository;
        this.dataSourceRepository = dataSourceRepository;
        this.regionRepository = regionRepository;
        this.categoryRepository = categoryRepository;
        this.regionResolver = regionResolver;
        this.baseDate = LocalDate.parse(baseDate);
    }

    public enum Outcome { ALREADY, REFRESHED, MATCHED, NONE }

    /**
     * 이미 적재한 업소는 CSV 내용이 그대로면 ALREADY(멱등), 메뉴·가격이 바뀌었거나 해제됐다 재지정됐으면 REFRESHED.
     * 기존 KTO 장소와 같은 가게면 플래그만 켜고 MATCHED. 이름이 같아도 주소 읍면동이 다르면 다른 가게다 - 신규(NONE).
     */
    @Transactional
    public Outcome upsertMatched(Row row) {
        String sourceId = sourceIdOf(row);
        Optional<PlaceSourceMapping> existing = mappingRepository.findBySourceCodeAndSourcePlaceId(SOURCE, sourceId);
        if (existing.isPresent()) {
            PlaceSourceMapping mapping = existing.get();
            String hash = hashOf(row);
            if (mapping.isActive() && hash.equals(mapping.getDataHash())) {
                return Outcome.ALREADY;
            }
            mapping.getPlace().markGoodPrice(baseDate, row.menuText(), row.phone());
            mapping.markSynced(hash, rawOf(row), null);
            mapping.activate();
            return Outcome.REFRESHED;
        }
        String normalized = PlaceNameNormalizer.normalize(row.name());
        List<Place> candidates = placeRepository.findByNormalizedName(normalized);
        for (Place place : candidates) {
            if (sameTown(place.getRoadAddress(), row.address())
                    || sameTown(place.getLotAddress(), row.address())) {
                place.markGoodPrice(baseDate, row.menuText(), row.phone());
                saveMapping(place, sourceId, row);
                return Outcome.MATCHED;
            }
        }
        return Outcome.NONE;
    }

    /** 신규 삽입. 권역 판정 불가(추자도 등)면 false. */
    @Transactional
    public boolean insertNew(Row row, GeoPoint geo) {
        String regionCode = regionResolver.resolve(row.address());
        if (regionCode == null) {
            return false;
        }
        Region region = regionRepository.findByCode(regionCode).orElseThrow();
        PlaceCategory food = categoryRepository.findByCode("FOOD").orElseThrow();

        Place place = placeRepository.save(Place.builder()
                .region(region)
                .primaryCategory(food)
                .name(row.name())
                .normalizedName(PlaceNameNormalizer.normalize(row.name()))
                .roadAddress(row.address())
                .latitude(geo.latitude())
                .longitude(geo.longitude())
                .phone(row.phone())
                .overview(row.menuText())
                .isGoodPrice(true)
                .goodPriceBaseDate(baseDate)
                .isHiddenGem(false)
                .reviewCount(0)
                .build());
        saveMapping(place, sourceIdOf(row), row);
        return true;
    }

    private void saveMapping(Place place, String sourceId, Row row) {
        DataSource source = dataSourceRepository.findById(SOURCE).orElseThrow();
        mappingRepository.save(PlaceSourceMapping.builder()
                .place(place)
                .source(source)
                .sourcePlaceId(sourceId)
                .dataHash(hashOf(row))
                .rawPayload(rawOf(row))
                .lastSyncedAt(LocalDateTime.now())
                .build());
    }

    /** CSV 에서 빠진 업소 - 지정 해제. 폐업 판정은 하지 않는다(해제 ≠ 폐업). 매핑은 남겨 재지정 때 되살린다. */
    @Transactional
    public ClearResult clearMissing(Set<String> seenSourceIds) {
        List<PlaceSourceMapping> all = mappingRepository.findAllBySourceCodeWithPlace(SOURCE);
        long active = all.stream().filter(PlaceSourceMapping::isActive).count();
        // CSV 가 깨져 몇 줄만 읽힌 날 전부 해제하는 사고 방지 - 출석 체크와 같은 80% 선
        if (active > 0 && seenSourceIds.size() < active * 0.8) {
            return new ClearResult(0, true);
        }
        int cleared = 0;
        for (PlaceSourceMapping m : all) {
            if (!m.isActive() || seenSourceIds.contains(m.getSourcePlaceId())) {
                continue;
            }
            m.getPlace().clearGoodPrice();
            m.deactivate();
            cleared++;
        }
        return new ClearResult(cleared, false);
    }

    /** skipped=true 면 CSV 수신이 부족해 해제를 보류한 것 */
    public record ClearResult(int cleared, boolean skipped) {
    }

    private static String rawOf(Row row) {
        return "{\"name\":\"" + row.name() + "\",\"address\":\"" + row.address() + "\"}";
    }

    /** 메뉴·가격·전화의 해시 - 재실행 때 바뀐 업소만 갱신한다 */
    static String hashOf(Row row) {
        return sha256(row.menuText() + "|" + row.phone());
    }

    /** CSV 에 고유 ID 가 없어 업소명|주소 해시를 쓴다 - 재실행 멱등의 키 */
    static String sourceIdOf(Row row) {
        return sha256(row.name() + "|" + row.address()).substring(0, 32);
    }

    private static String sha256(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 같은 읍·면·동에 있으면 같은 가게로 본다 (이름 동일 전제) */
    static boolean sameTown(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        String townA = townOf(a);
        return !townA.isEmpty() && townA.equals(townOf(b));
    }

    private static String townOf(String address) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("([가-힣]+(읍|면|동))").matcher(address);
        return m.find() ? m.group(1) : "";
    }
}
