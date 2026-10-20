package com.example.hangat.map.goodprice;

import com.example.hangat.map.goodprice.GoodPriceCsv.Row;
import com.example.hangat.map.goodprice.KakaoLocalClient.GeoPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Optional;

/**
 * 착한가격업소 적재 (MAP-04) - 행안부 CSV(2026-06-30) → places.
 *
 * API 미제공이라 CSV 를 리소스로 품고 간다. 외식업만 넣는다 - 여행 지도에
 * 미용실·세탁소는 소음이다(W0 실측: 393곳 중 외식 284곳).
 * 좌표가 없어 카카오 로컬로 지오코딩한다(업소당 1콜, 카카오 일 한도 10만).
 */
@Service
public class GoodPriceIngestService {

    private static final Logger log = LoggerFactory.getLogger(GoodPriceIngestService.class);
    private static final String CSV_PATH = "/data/goodprice_jeju.csv";

    private final KakaoLocalClient kakao;
    private final GoodPriceIngestWriter writer;

    public GoodPriceIngestService(KakaoLocalClient kakao, GoodPriceIngestWriter writer) {
        this.kakao = kakao;
        this.writer = writer;
    }

    /**
     * @param matched  기존 KTO 장소에 플래그만 켠 수
     * @param inserted 새로 만든 장소 수
     * @param skippedGeo 지오코딩 실패로 뺀 수 (좌표 없이는 지도에 못 찍는다)
     * @param skippedRegion 권역 판정 불가(추자도 등)로 뺀 수
     * @param refreshed     CSV 내용이 바뀌어 메뉴·가격·기준일을 갱신한 수
     * @param cleared       CSV 에서 빠져 지정을 해제한 수
     * @param clearSkipped  CSV 수신이 부족해 해제를 보류함 - 배치는 이 경우를 실패로 기록한다
     */
    public record GoodPriceResult(int totalCsv, int food, int matched, int inserted,
                                  int skippedGeo, int skippedRegion, int unchanged,
                                  int refreshed, int cleared, boolean clearSkipped) {
    }

    public GoodPriceResult ingest() {
        List<Row> all;
        try (InputStream in = getClass().getResourceAsStream(CSV_PATH)) {
            all = GoodPriceCsv.parse(in);
        } catch (Exception e) {
            throw new IllegalStateException("착한가격 CSV 로드 실패", e);
        }
        List<Row> food = all.stream().filter(r -> GoodPriceCsv.isFood(r.category())).toList();
        log.info("착한가격 적재 시작: CSV {}건 중 외식 {}건", all.size(), food.size());

        int matched = 0;
        int inserted = 0;
        int skippedGeo = 0;
        int skippedRegion = 0;
        int unchanged = 0;
        int refreshed = 0;
        Set<String> seen = new HashSet<>();   // 이번 CSV 에 있는 업소 - 끝나고 빠진 업소를 해제한다

        for (Row row : food) {
            seen.add(GoodPriceIngestWriter.sourceIdOf(row));
            GoodPriceIngestWriter.Outcome outcome = writer.upsertMatched(row);
            if (outcome == GoodPriceIngestWriter.Outcome.MATCHED) {
                matched++;
                continue;
            }
            if (outcome == GoodPriceIngestWriter.Outcome.REFRESHED) {
                refreshed++;
                continue;
            }
            if (outcome == GoodPriceIngestWriter.Outcome.ALREADY) {
                unchanged++;
                continue;
            }
            // 신규 - 좌표가 있어야 지도에 찍힌다
            Optional<GeoPoint> geo = kakao.geocode(row.address());
            if (geo.isEmpty()) {
                skippedGeo++;
                log.debug("지오코딩 실패로 제외: {} ({})", row.name(), row.address());
                continue;
            }
            if (writer.insertNew(row, geo.get())) {
                inserted++;
            } else {
                skippedRegion++;
            }
        }

        // CSV 에서 빠진 업소 해제 - 저장이 다 끝난 뒤 한 번
        GoodPriceIngestWriter.ClearResult cleared = writer.clearMissing(seen);
        GoodPriceResult result = new GoodPriceResult(
                all.size(), food.size(), matched, inserted, skippedGeo, skippedRegion, unchanged,
                refreshed, cleared.cleared(), cleared.skipped());
        log.info("착한가격 적재 완료 {}", result);
        return result;
    }
}
