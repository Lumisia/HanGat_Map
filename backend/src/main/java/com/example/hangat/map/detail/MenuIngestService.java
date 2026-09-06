package com.example.hangat.map.detail;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.map.client.PublicApiClient;
import com.example.hangat.map.detail.model.PlaceIntroItem;
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
import java.util.List;
import java.util.Map;

/**
 * 음식점 대표 메뉴 적재 (MAP-07 메뉴 섹션) - KTO {@code detailIntro2}의 firstmenu·treatmenu
 *
 * <p>상세 적재({@link PlaceDetailIngestService})가 같은 API를 이미 돌았지만 당시엔 메뉴 필드를
 * 담지 않았다. 메뉴(overview)가 비어 있는 음식점만 다시 1콜씩 돈다 - 음식점은 전부 타입 39라
 * 상세 적재와 달리 타입 맵이 필요 없다.
 *
 * <p>가격은 KTO가 주지 않는다. 착한가격(overview)과 같은 "대표메뉴: A · B" 형식으로 저장해
 * 화면 메뉴 섹션을 그대로 재사용한다.
 *
 * <p>쿼터 초과(코드 22)를 만나면 <b>즉시 멈춘다</b> - 계속 때리면 다음 날 몫까지 태운다.
 */
@Service
public class MenuIngestService {

    private static final Logger log = LoggerFactory.getLogger(MenuIngestService.class);

    private static final String PATH = "/KorService2/detailIntro2";
    private static final String FOOD_TYPE = "39";

    /** 대상이 713곳이라 한 번에 끝나는 값. 같은 날 사진 적재와 쿼터(1,000콜)를 나눠 쓴다면 낮춰서 실행. */
    public static final int DEFAULT_LIMIT = 800;

    /** 한 트랜잭션에 담는 건수. 중간에 끊겨도 앞부분은 남는다. */
    private static final int CHUNK = 100;

    private final PublicApiClient client;
    private final PlaceRepository placeRepository;
    private final PlaceSourceMappingRepository mappingRepository;
    private final DetailFieldMapper mapper;
    private final MenuIngestWriter writer;

    public MenuIngestService(PublicApiClient client,
                             PlaceRepository placeRepository,
                             PlaceSourceMappingRepository mappingRepository,
                             DetailFieldMapper mapper,
                             MenuIngestWriter writer) {
        this.client = client;
        this.placeRepository = placeRepository;
        this.mappingRepository = mappingRepository;
        this.mapper = mapper;
        this.writer = writer;
    }

    /**
     * @param requested 처리 대상 수. 쿼터를 넘지 않게 호출자가 조절한다
     * @param remaining 이번에 못 한 나머지. KTO가 메뉴를 안 주는 곳(empty)도 포함되어 0이 안 될 수 있다
     */
    public record MenuIngestResult(int requested, int called, int updated, int empty,
                                   int skippedNoSourceId, int failed, int remaining, boolean quotaExceeded) {
    }

    public MenuIngestResult ingest(int limit) {
        List<Place> targets = placeRepository.findFoodWithoutMenu(PageRequest.of(0, limit));
        log.info("메뉴 적재 시작: 대상 {}건 (limit={})", targets.size(), limit);

        List<MenuIngestWriter.Row> rows = new ArrayList<>();
        int called = 0;
        int noSourceId = 0;
        int failed = 0;
        boolean quotaExceeded = false;

        for (Place place : targets) {
            String contentId = mappingRepository
                    .findByPlaceIdAndSourceCode(place.getId(), "KTO")
                    .map(m -> m.getSourcePlaceId())
                    .orElse(null);
            // 착한가격 CSV 출처(메뉴 없이 등록된 소수)는 KTO 매핑이 없어 여기서 걸러진다
            if (contentId == null) {
                noSourceId++;
                continue;
            }

            try {
                TourApiResponse<PlaceIntroItem> res = client.get(PATH,
                        Map.of("contentId", contentId, "contentTypeId", FOOD_TYPE),
                        new TypeReference<TourApiResponse<PlaceIntroItem>>() {
                        });
                called++;
                List<PlaceIntroItem> items = res.items();
                rows.add(new MenuIngestWriter.Row(place.getId(),
                        items.isEmpty() ? null : mapper.menuText(items.get(0))));
            } catch (BaseException e) {
                // 쿼터 초과면 멈춘다 - PlaceDetailIngestService 와 같은 가드
                if (String.valueOf(e.getResult()).contains("QUOTA_EXCEEDED")) {
                    log.error("일일 트래픽 초과 - 여기서 중단하고 지금까지 받은 것만 저장한다");
                    quotaExceeded = true;
                    break;
                }
                failed++;
                log.debug("메뉴 조회 실패 place={} contentId={}", place.getName(), contentId);
            }
        }

        int updated = 0;
        int empty = 0;
        for (int i = 0; i < rows.size(); i += CHUNK) {
            MenuIngestWriter.ChunkResult r =
                    writer.saveChunk(rows.subList(i, Math.min(i + CHUNK, rows.size())));
            updated += r.updated();
            empty += r.empty();
        }

        int remaining = placeRepository.findFoodWithoutMenu(PageRequest.of(0, Integer.MAX_VALUE)).size();
        MenuIngestResult result = new MenuIngestResult(
                targets.size(), called, updated, empty, noSourceId, failed, remaining, quotaExceeded);
        log.info("메뉴 적재 완료 {}", result);
        return result;
    }
}
