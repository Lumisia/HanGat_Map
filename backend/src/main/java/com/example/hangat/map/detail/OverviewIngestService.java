package com.example.hangat.map.detail;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.map.client.PublicApiClient;
import com.example.hangat.map.detail.model.PlaceCommonItem;
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
 * 관광지 소개글 적재 (MAP_008 상세 패널 '소개') - KTO {@code detailCommon2}의 overview.
 *
 * <p>상세 적재({@link PlaceDetailIngestService})는 {@code detailIntro2}(운영시간·휴무·주차·요금)만 돌았고
 * 소개글은 다른 API라 한 번도 적재된 적이 없었다(관광지 812곳 overview 0건, 2026-09-11 실측).
 * 소개글이 비어 있는 <b>관광지</b>만 1콜씩 돈다 - 음식점은 같은 컬럼을 메뉴 문단이 쓰므로 대상이 아니다.
 *
 * <p>원문은 고치지 않는다(공모전 규정: 원천데이터 수정 비권장). {@code <br>}·HTML 태그·엔티티만 정리한다.
 * 쿼터 초과(코드 22)를 만나면 <b>즉시 멈춘다</b> - 계속 때리면 다음 날 몫까지 태운다.
 */
@Service
public class OverviewIngestService {

    private static final Logger log = LoggerFactory.getLogger(OverviewIngestService.class);
    private static final String PATH = "/KorService2/detailCommon2";
    /** 관광지 812곳 - 하루 쿼터(1,000콜) 안에 한 번에 끝나는 값. 같은 날 다른 적재와 나눠 쓰면 낮춰서 실행 */
    public static final int DEFAULT_LIMIT = 900;
    /** 한 트랜잭션에 담는 건수. 중간에 끊겨도 앞부분은 남는다. */
    private static final int CHUNK = 100;

    private final PublicApiClient client;
    private final PlaceRepository placeRepository;
    private final PlaceSourceMappingRepository mappingRepository;
    private final OverviewIngestWriter writer;

    public OverviewIngestService(PublicApiClient client,
                                 PlaceRepository placeRepository,
                                 PlaceSourceMappingRepository mappingRepository,
                                 OverviewIngestWriter writer) {
        this.client = client;
        this.placeRepository = placeRepository;
        this.mappingRepository = mappingRepository;
        this.writer = writer;
    }

    /**
     * @param requested 처리 대상 수. 쿼터를 넘지 않게 호출자가 조절한다
     * @param remaining 이번에 못 한 나머지. KTO가 소개글을 안 주는 곳(empty)도 포함되어 0이 안 될 수 있다
     */
    public record OverviewIngestResult(int requested, int called, int updated, int empty,
                                       int skippedNoSourceId, int failed, int remaining, boolean quotaExceeded) {
    }

    public OverviewIngestResult ingest(int limit) {
        List<Place> targets = placeRepository.findTouristWithoutOverview(PageRequest.of(0, limit));
        log.info("소개글 적재 시작: 대상 {}건 (limit={})", targets.size(), limit);

        List<OverviewIngestWriter.Row> rows = new ArrayList<>();
        int called = 0;
        int noSourceId = 0;
        int failed = 0;
        boolean quotaExceeded = false;
        for (Place place : targets) {
            String contentId = mappingRepository
                    .findByPlaceIdAndSourceCode(place.getId(), "KTO")
                    .map(m -> m.getSourcePlaceId())
                    .orElse(null);
            if (contentId == null) {
                noSourceId++;
                continue;
            }
            try {
                TourApiResponse<PlaceCommonItem> res = client.get(PATH, Map.of("contentId", contentId),
                        new TypeReference<TourApiResponse<PlaceCommonItem>>() {
                        });
                called++;
                List<PlaceCommonItem> items = res.items();
                rows.add(new OverviewIngestWriter.Row(place.getId(),
                        items.isEmpty() ? null : clean(items.get(0).overview())));
            } catch (BaseException e) {
                // 쿼터 초과면 멈춘다 - PlaceDetailIngestService 와 같은 가드
                if (String.valueOf(e.getResult()).contains("QUOTA_EXCEEDED")) {
                    log.error("일일 트래픽 초과 - 여기서 중단하고 지금까지 받은 것만 저장한다");
                    quotaExceeded = true;
                    break;
                }
                failed++;
                log.debug("소개글 조회 실패 place={} contentId={}", place.getName(), contentId);
            }
        }

        int updated = 0;
        int empty = 0;
        for (int i = 0; i < rows.size(); i += CHUNK) {
            OverviewIngestWriter.ChunkResult r =
                    writer.saveChunk(rows.subList(i, Math.min(i + CHUNK, rows.size())));
            updated += r.updated();
            empty += r.empty();
        }

        int remaining = placeRepository.findTouristWithoutOverview(PageRequest.of(0, Integer.MAX_VALUE)).size();
        OverviewIngestResult result = new OverviewIngestResult(
                targets.size(), called, updated, empty, noSourceId, failed, remaining, quotaExceeded);
        log.info("소개글 적재 완료 {}", result);
        return result;
    }

    /**
     * KTO 원문 정리 - 내용은 그대로 두고 표기만 손본다: {@code <br>}은 줄바꿈, 나머지 태그 제거,
     * 자주 오는 HTML 엔티티 복원, 3줄 넘는 빈 줄은 2줄로. 비면 null.
     */
    static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("<[^>]+>", "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replaceAll("[ \\t]+\n", "\n")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
        return t.isEmpty() ? null : t;
    }
}
