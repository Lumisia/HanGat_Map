package com.example.hangat.map.store;

import com.example.hangat.map.place.PlacePresenceReconciler;
import com.example.hangat.map.store.SbizStoreClient.StoreItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 카페·편의점·마트 적재 (MAP-04 나머지) - 소상공인 상가정보 → places.
 * 좌표가 응답에 포함돼 있어 지오코딩이 필요 없다(착한가격과 다른 점).
 */
@Service
public class StoreIngestService {

    private static final Logger log = LoggerFactory.getLogger(StoreIngestService.class);

    /** 업종 소분류 → 우리 카테고리 (실측: 카페 3,048 / 편의점 1,324 / 슈퍼마켓 1,067) */
    private static final Map<String, String> UPJONG_TO_CATEGORY = Map.of(
            "I21201", "CAFE",
            "G20405", "CONVENIENCE",
            "G20404", "MART");

    private final SbizStoreClient client;
    private final StoreIngestWriter writer;
    private final PlacePresenceReconciler reconciler;

    public StoreIngestService(SbizStoreClient client, StoreIngestWriter writer,
                              PlacePresenceReconciler reconciler) {
        this.client = client;
        this.writer = writer;
        this.reconciler = reconciler;
    }

    public record StoreIngestResult(Map<String, Integer> fetched, int inserted, int unchanged,
                                    int skippedNoCoord, int skippedRegion,
                                    PlacePresenceReconciler.Result presence) {
    }

    public StoreIngestResult ingest() {
        Map<String, Integer> fetched = new LinkedHashMap<>();
        int inserted = 0;
        int unchanged = 0;
        int noCoord = 0;
        int noRegion = 0;
        Set<String> seen = new HashSet<>();   // 출석 체크용 - 좌표·권역으로 거르기 전의 상가 ID 전부

        for (Map.Entry<String, String> e : UPJONG_TO_CATEGORY.entrySet()) {
            List<StoreItem> items = client.fetchAll(e.getKey());
            fetched.put(e.getValue(), items.size());
            for (StoreItem item : items) {
                if (item.bizesId() != null) {
                    seen.add(item.bizesId());
                }
                if (item.latitude() == null || item.longitude() == null) {
                    noCoord++;
                    continue;
                }
                switch (writer.upsert(item, e.getValue())) {
                    case INSERTED -> inserted++;
                    case ALREADY -> unchanged++;
                    case NO_REGION -> noRegion++;
                }
            }
        }
        PlacePresenceReconciler.Result presence = reconciler.reconcile(StoreIngestWriter.SOURCE, seen);
        StoreIngestResult result = new StoreIngestResult(fetched, inserted, unchanged, noCoord, noRegion, presence);
        log.info("소상공인 적재 완료 {}", result);
        return result;
    }
}
