package com.example.hangat.map.place;

import com.example.hangat.map.service.PlaceNameNormalizer;
import com.example.hangat.map.service.RegionResolver;
import com.example.hangat.map.client.PublicApiClient;
import com.example.hangat.map.model.dto.KtoPlaceItem;
import com.example.hangat.map.model.dto.TourApiResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * KTO 관광정보를 places 테이블로 적재한다 - 설계서 §3.2 / 커밋 2-2
 *
 * <p>제주 전역 2,147건을 목록 API 한 종류로 받아온다(2026-08-23 실측).
 * 개요·운영시간 같은 상세는 장소당 1콜씩 더 필요해 커밋 3-3에서 따로 채운다 -
 * 목록만으로도 이름·좌표·주소·분류가 다 오므로 지도는 이 단계에서 이미 동작한다.
 */
@Service
public class PlaceIngestService {

    private static final Logger log = LoggerFactory.getLogger(PlaceIngestService.class);

    private static final String SOURCE_CODE = "KTO";
    private static final String PATH = "/KorService2/areaBasedList2";
    private static final String JEJU = "50";

    /** 한 트랜잭션에 담는 건수. 실패해도 앞부분은 남는다. */
    private static final int CHUNK = 100;

    private static final DateTimeFormatter KTO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /**
     * KTO 콘텐츠 타입 → 우리 카테고리.
     * 관광지 성격(문화시설·축제·레포츠)은 TOURIST로 합친다 - 화면 필터가 7종뿐이라
     * 더 쪼개도 보여줄 자리가 없다. 25(여행코스)는 제주 0건이라 매핑하지 않는다.
     */
    private static final Map<String, String> TYPE_TO_CATEGORY = Map.of(
            "12", "TOURIST",   // 관광지 563
            "14", "TOURIST",   // 문화시설 98
            "15", "TOURIST",   // 축제공연 28
            "28", "TOURIST",   // 레포츠 137
            "32", "LODGING",   // 숙박 210
            "38", "SHOPPING",  // 쇼핑 395
            "39", "FOOD"       // 음식점 716
    );

    /** KTO 음식점(39) 중 세부분류가 카페·찻집이면 CAFE 로 넣는다 - 39 를 통째로 FOOD 로 넣어 관광공사 카페 224곳이
     *  식당 칩에 섞이고 카페 칩엔 소상공인 상가(메뉴·사진 없음)만 있었다(2026-09-14). 사진·메뉴가 있는 카페가 카페 칩으로 간다 */
    private static final Set<String> CAFE_TAGS = Set.of("FD050100", "FD050200");   // 카페, 찻집

    /** 콘텐츠 타입 + 세부분류 → 우리 카테고리. 매핑 없는 타입은 null */
    static String categoryFor(String contentTypeId, String tagCode) {
        String category = TYPE_TO_CATEGORY.get(contentTypeId);
        if ("FOOD".equals(category) && tagCode != null && CAFE_TAGS.contains(tagCode)) return "CAFE";
        return category;
    }

    private final PublicApiClient client;
    private final RegionResolver regionResolver;
    private final PlaceIngestWriter writer;
    private final TagSyncService tagSyncService;
    private final PlacePresenceReconciler reconciler;

    public PlaceIngestService(PublicApiClient client, RegionResolver regionResolver,
                              PlaceIngestWriter writer, TagSyncService tagSyncService,
                              PlacePresenceReconciler reconciler) {
        this.client = client;
        this.regionResolver = regionResolver;
        this.writer = writer;
        this.tagSyncService = tagSyncService;
        this.reconciler = reconciler;
    }

    /** 적재 결과 요약. 제외 건수를 함께 돌려줘 "왜 2,147이 아닌지" 바로 알 수 있게 한다. */
    public record IngestResult(int fetched, int inserted, int updated, int unchanged,
                               int skippedNoRegion, int skippedNoCategory, int skippedNoId,
                               int tagged, int skippedNoTag,
                               PlacePresenceReconciler.Result presence) {
    }

    public IngestResult ingest() {
        // 장소가 참조할 태그를 먼저 채운다. 1콜이고 별도 트랜잭션이라 여기서 이미 커밋이 끝난다
        tagSyncService.sync();

        List<KtoPlaceItem> items = client.fetchAll(PATH, Map.of("lDongRegnCd", JEJU),
                new TypeReference<TourApiResponse<KtoPlaceItem>>() {
                });
        log.info("KTO 목록 수신: {}건", items.size());

        List<PlaceIngestWriter.Row> rows = new ArrayList<>();
        int noRegion = 0;
        int noCategory = 0;
        int noId = 0;
        int noTag = 0;
        Set<String> seen = new HashSet<>();   // 출석 체크용 - 권역·카테고리로 거르기 전의 원천 ID 전부

        for (KtoPlaceItem item : items) {
            if (isBlank(item.contentid()) || isBlank(item.title())) {
                noId++;
                continue;
            }
            seen.add(item.contentid());
            String categoryCode = categoryFor(item.contenttypeid(), blankToNull(item.lclsSystm3()));
            if (categoryCode == null) {
                noCategory++;
                log.debug("매핑 없는 contenttypeid={} title={}", item.contenttypeid(), item.title());
                continue;
            }
            String address = join(item.addr1(), item.addr2());
            String regionCode = regionResolver.resolve(address);
            if (regionCode == null) {
                noRegion++;
                log.debug("권역 판정 실패 title={} addr={}", item.title(), address);
                continue;
            }

            String tagCode = blankToNull(item.lclsSystm3());
            if (tagCode == null) {
                noTag++;   // 분류가 없어도 장소는 넣는다 - 지도에는 찍혀야 하니까
                log.debug("세부분류 없음 title={}", item.title());
            }

            rows.add(new PlaceIngestWriter.Row(
                    item.contentid(), regionCode, categoryCode, tagCode,
                    item.title(), normalize(item.title()), blankToNull(item.addr1()),
                    // ★ mapy=위도, mapx=경도. 순서를 바꾸면 제주 전역 핀이 통째로 어긋난다
                    toDecimal(item.mapy(), LAT_MIN, LAT_MAX, "위도", item.title()),
                    toDecimal(item.mapx(), LNG_MIN, LNG_MAX, "경도", item.title()),
                    blankToNull(item.tel()),
                    blankToNull(item.firstimage()),
                    hash(item), null, parseTime(item.modifiedtime())));
        }

        int inserted = 0;
        int updated = 0;
        int unchanged = 0;
        int tagged = 0;
        for (int i = 0; i < rows.size(); i += CHUNK) {
            List<PlaceIngestWriter.Row> chunk = rows.subList(i, Math.min(i + CHUNK, rows.size()));
            PlaceIngestWriter.ChunkResult r = writer.saveChunk(SOURCE_CODE, chunk);
            inserted += r.inserted();
            updated += r.updated();
            unchanged += r.unchanged();
            tagged += r.tagged();
        }

        // 목록에서 사라진 장소 판정 - 저장이 다 끝난 뒤 한 번
        PlacePresenceReconciler.Result presence = reconciler.reconcile(SOURCE_CODE, seen);
        IngestResult result = new IngestResult(items.size(), inserted, updated, unchanged,
                noRegion, noCategory, noId, tagged, noTag, presence);
        log.info("KTO 적재 완료 {}", result);
        if (noRegion > 0) {
            log.warn("권역 판정 실패로 제외 {}건 - 추자도 등 본섬 밖은 의도된 제외다(설계서 §5.1)", noRegion);
        }
        return result;
    }

    // ── 변환 ─────────────────────────────────────────────────────────

    /**
     * 집중률 API 매칭용 정규화 이름.
     * 집중률은 좌표도 고유 ID도 없이 관광지명만 주므로 표기 흔들림
     * ('성산일출봉' vs '성산 일출봉')을 흡수해야 한다(설계서 §3.6).
     *
     * <p>규칙 본체는 {@link PlaceNameNormalizer}에 있다 - 이 값을 만드는 쪽(여기)과
     * 쓰는 쪽({@link CongestionIngestService})이 <b>반드시 같은 규칙</b>이어야 하기 때문이다.
     */
    private String normalize(String name) {
        return PlaceNameNormalizer.normalize(name);
    }

    /**
     * 제주 좌표 허용 범위. 마라도(33.06)~제주시 북단(33.57), 고산(126.16)~성산(126.97)에
     * 여유를 둔 값이다. 추자도(33.9)는 어차피 권역 판정에서 걸러진다.
     */
    private static final BigDecimal LAT_MIN = new BigDecimal("32.9");
    private static final BigDecimal LAT_MAX = new BigDecimal("33.7");
    private static final BigDecimal LNG_MIN = new BigDecimal("125.9");
    private static final BigDecimal LNG_MAX = new BigDecimal("127.1");

    /**
     * KTO는 좌표를 문자열로 준다. 값이 없거나 형식이 깨졌으면 null - 0으로 채우지 않는다(§1.2).
     *
     * <p><b>범위 검증이 필요한 이유</b>: KTO 원본에 잘못된 좌표가 섞여 있다.
     * 실측 예 - '영주산'(서귀포시 표선면)의 {@code mapx}가 {@code 12.79737228191}로,
     * {@code 126.797...}에서 앞자리가 빠진 값이다(2026-08-23 확인).
     * 그대로 넣으면 지도에 제주 밖으로 핀이 찍히므로, 범위를 벗어나면 <b>좌표 없음(null)</b>으로 둔다.
     * 추측으로 보정하지 않는다 - 틀린 값을 그럴듯하게 만드는 것보다 '정보 없음'이 정직하다.
     */
    private BigDecimal toDecimal(String raw, BigDecimal min, BigDecimal max, String label, String title) {
        if (isBlank(raw)) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(raw.trim());
            if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
                log.warn("좌표 범위 이탈로 제외 - {} {}={} title={} (KTO 원본 오류)", label, label, value, title);
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private LocalDateTime parseTime(String raw) {
        if (isBlank(raw) || raw.trim().length() != 14) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw.trim(), KTO_TIME);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 변경 감지용. 다음 배치에서 이 값이 같으면 UPDATE를 건너뛴다.
     *
     * <p>⚠️ <b>새로 쓰기 시작한 필드는 반드시 씨앗에 넣어야 한다.</b> 씨앗에 없으면 해시가 그대로라
     * 기존 행이 전부 '변경 없음'으로 판정돼 새 필드가 영원히 안 채워진다.
     * {@code lclsSystm3}(세부분류)를 넣은 이유가 이것이다 - 넣지 않으면 이미 적재된
     * 2,138건에 태그가 하나도 안 붙는다.
     */
    private String hash(KtoPlaceItem item) {
        // HASH_VERSION: 저장 스키마가 넓어질 때 올린다. 올리면 전 행의 해시가 달라져
        // 다음 적재 때 1회 전체 UPDATE가 돌며 새 컬럼이 백필된다 (v2: image_url 추가)
        String seed = String.join("|", "v2",
                nz(item.title()), nz(item.addr1()), nz(item.addr2()), nz(item.tel()),
                nz(item.mapx()), nz(item.mapy()), nz(item.contenttypeid()),
                nz(item.firstimage()), nz(item.modifiedtime()), nz(item.lclsSystm3()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            return null;   // 해시를 못 구하면 항상 갱신하는 쪽으로 동작한다
        }
    }

    private String join(String a, String b) {
        if (isBlank(b)) {
            return nz(a);
        }
        return (nz(a) + " " + b.trim()).trim();
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private String blankToNull(String s) {
        return isBlank(s) ? null : s.trim();
    }

    private String nz(String s) {
        return s == null ? "" : s.trim();
    }
}
