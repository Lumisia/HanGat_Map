package com.example.hangat.map.detail;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.map.client.PublicApiClient;
import com.example.hangat.map.detail.model.PlaceIntroItem;
import com.example.hangat.map.model.dto.KtoPlaceItem;
import com.example.hangat.map.model.dto.TourApiResponse;
import com.example.hangat.map.model.entity.Place;
import com.example.hangat.map.repository.PlaceRepository;
import com.example.hangat.map.repository.PlaceSourceMappingRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 장소 상세 정보 적재 (MAP-07) - KTO {@code detailIntro2}
 *
 * <p><b>장소당 1콜</b>이라 2,138곳이면 일일 쿼터(1,000콜)를 넘는다. 한 번에 다 못 돌므로
 * 상세가 비어 있는 장소부터 {@code limit}만큼 처리하고, 다시 돌리면 이어진다.
 * 커서를 저장하지 않는 이유는 {@link PlaceRepository#findWithoutDetail} 참고.
 *
 * <p>쿼터 초과(코드 22)를 만나면 <b>즉시 멈춘다</b> - 계속 때리면 다음 날 몫까지 태운다.
 */
@Service
public class PlaceDetailIngestService {

    private static final Logger log = LoggerFactory.getLogger(PlaceDetailIngestService.class);

    private static final String PATH = "/KorService2/detailIntro2";
    private static final String LIST_PATH = "/KorService2/areaBasedList2";
    private static final String JEJU = "50";

    /** 일일 쿼터 1,000콜에서 여유를 둔 기본값. */
    public static final int DEFAULT_LIMIT = 900;

    /** 한 트랜잭션에 담는 건수. 중간에 끊겨도 앞부분은 남는다. */
    private static final int CHUNK = 100;

    /**
     * 타입 맵을 못 구했을 때 쓰는 대비책. 정확하지 않다 -
     * 적재 때 KTO 12·14·15·28을 TOURIST 하나로 합쳐서 원래 타입을 복원할 수 없다.
     */
    private static final Map<String, String> CATEGORY_TO_TYPE = Map.of(
            "TOURIST", "12", "LODGING", "32", "SHOPPING", "38", "FOOD", "39", "CAFE", "39");   // KTO 카페는 음식점(39) 세부분류

    private final PublicApiClient client;
    private final PlaceRepository placeRepository;
    private final PlaceSourceMappingRepository mappingRepository;
    private final PlaceDetailIngestWriter writer;

    public PlaceDetailIngestService(PublicApiClient client,
                                    PlaceRepository placeRepository,
                                    PlaceSourceMappingRepository mappingRepository,
                                    PlaceDetailIngestWriter writer) {
        this.client = client;
        this.placeRepository = placeRepository;
        this.mappingRepository = mappingRepository;
        this.writer = writer;
    }

    /**
     * @param requested 처리 대상 수. 쿼터를 넘지 않게 호출자가 조절한다
     * @param remaining 이번에 못 한 나머지. 0이 될 때까지 다시 돌리면 된다
     */
    public record DetailIngestResult(int requested, int called, int updated, int empty,
                                     int skippedNoSourceId, int failed, int remaining, boolean quotaExceeded) {
    }

    public DetailIngestResult ingest(int limit) {
        Map<String, String> typeByContentId = loadContentTypeMap();

        List<Place> targets = placeRepository.findWithoutDetail(PageRequest.of(0, limit));
        log.info("상세 적재 시작: 대상 {}건 (limit={}, 타입맵 {}건)", targets.size(), limit, typeByContentId.size());

        List<PlaceDetailIngestWriter.Row> rows = new ArrayList<>();
        int called = 0;
        int noSourceId = 0;
        int failed = 0;
        boolean quotaExceeded = false;

        for (Place place : targets) {
            String contentId = mappingRepository
                    .findByPlaceIdAndSourceCode(place.getId(), "KTO")
                    .map(m -> m.getSourcePlaceId())
                    .orElse(null);
            // ★ 목록에서 받은 원래 contenttypeid 를 쓴다.
            //   카테고리로 되돌리면 문화시설·축제·레포츠(263곳)가 전부 12로 불려
            //   usefee 같은 타입 전용 필드를 못 받는다(입장료 0건이 됐던 원인)
            String typeId = contentId == null ? null : typeByContentId.get(contentId);
            if (typeId == null) {
                typeId = CATEGORY_TO_TYPE.get(place.getPrimaryCategory().getCode());
            }
            if (contentId == null || typeId == null) {
                noSourceId++;
                continue;
            }

            try {
                TourApiResponse<PlaceIntroItem> res = client.get(PATH,
                        Map.of("contentId", contentId, "contentTypeId", typeId),
                        new TypeReference<TourApiResponse<PlaceIntroItem>>() {
                        });
                called++;
                List<PlaceIntroItem> items = res.items();
                if (!items.isEmpty()) {
                    rows.add(new PlaceDetailIngestWriter.Row(place.getId(), items.get(0)));
                }
            } catch (BaseException e) {
                // 쿼터 초과면 멈춘다. 계속 때리면 다음 날 몫까지 태운다(설계서 §3.4)
                // PublicApiClient 가 result 에 "포털 오류 22 ... (QUOTA_EXCEEDED, ...)" 형태로 담는다
                if (String.valueOf(e.getResult()).contains("QUOTA_EXCEEDED")) {
                    log.error("일일 트래픽 초과 - 여기서 중단하고 지금까지 받은 것만 저장한다");
                    quotaExceeded = true;
                    break;
                }
                failed++;
                log.debug("상세 조회 실패 place={} contentId={}", place.getName(), contentId);
            }
        }

        int updated = 0;
        int empty = 0;
        for (int i = 0; i < rows.size(); i += CHUNK) {
            PlaceDetailIngestWriter.ChunkResult r =
                    writer.saveChunk(rows.subList(i, Math.min(i + CHUNK, rows.size())));
            updated += r.updated();
            empty += r.empty();
        }

        int remaining = placeRepository.findWithoutDetail(PageRequest.of(0, Integer.MAX_VALUE)).size();
        DetailIngestResult result = new DetailIngestResult(
                targets.size(), called, updated, empty, noSourceId, failed, remaining, quotaExceeded);
        log.info("상세 적재 완료 {}", result);
        if (remaining > 0) {
            log.info("남은 {}건은 다시 실행하면 이어서 처리된다", remaining);
        }
        return result;
    }

    /**
     * contentId → contenttypeid 맵. 목록 API 3콜이면 제주 전역이 다 온다.
     *
     * <p>실패해도 배치를 죽이지 않는다 - 빈 맵이면 카테고리 기반 대비책으로 넘어간다.
     * 정확도는 떨어지지만 관광지·음식점·숙박·쇼핑은 여전히 맞는다.
     */
    private Map<String, String> loadContentTypeMap() {
        try {
            List<KtoPlaceItem> items = client.fetchAll(LIST_PATH, Map.of("lDongRegnCd", JEJU),
                    new TypeReference<TourApiResponse<KtoPlaceItem>>() {
                    });
            Map<String, String> map = new HashMap<>();
            for (KtoPlaceItem it : items) {
                if (it.contentid() != null && it.contenttypeid() != null) {
                    map.put(it.contentid(), it.contenttypeid());
                }
            }
            return map;
        } catch (Exception e) {
            log.warn("타입 맵 조회 실패 - 카테고리 기반으로 진행한다(문화시설·축제·레포츠는 값이 비게 된다): {}",
                    e.getMessage());
            return Map.of();
        }
    }
}
