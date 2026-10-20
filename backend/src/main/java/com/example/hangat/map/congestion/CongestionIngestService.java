package com.example.hangat.map.congestion;

import com.example.hangat.map.service.PlaceNameNormalizer;
import com.example.hangat.map.client.PublicApiClient;
import com.example.hangat.map.model.dto.CnctrRateItem;
import com.example.hangat.map.model.dto.TourApiResponse;
import com.example.hangat.map.repository.PlaceRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 관광지별 집중률을 congestion_forecasts로 적재한다 - 설계서 §3.5 / 커밋 4-2
 *
 * <p>화면 좌측 "한산한 곳 TOP 8"의 순위와 지도 핀 색이 이 데이터에서 나온다.
 *
 * <p><b>이 배치의 어려운 점은 호출이 아니라 이름 매칭이다.</b> 집중률 API는 고유 ID를 주지 않아
 * 관광지명 문자열로만 우리 장소와 이어붙일 수 있는데, 같은 한국관광공사인데도 두 서비스가
 * 같은 곳을 다르게 부른다 - 집중률의 "주상절리대"가 KorService2에서는 "대포주상절리"다.
 * 2026-08-24 실측 매칭률은 <b>448곳 중 347곳(77.5%)</b>이고, 실패분은 대부분
 * KTO 관광정보 목록에 아예 없는 장소라 정규화 규칙으로는 못 올린다.
 */
@Service
public class CongestionIngestService {

    private static final Logger log = LoggerFactory.getLogger(CongestionIngestService.class);

    private static final String SOURCE_CODE = "KTO_CNCTR";
    private static final String PATH = "/TatsCnctrRateService/tatsCnctrRatedList";
    private static final String JEJU = "50";

    /** ⚠️ 5자리다. KorService2의 시군구 코드(110/130)와 자릿수가 다르다(설계서 §3.3). */
    private static final List<String> SIGNGU_CODES = List.of("50110", "50130");

    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final BigDecimal RATE_MAX = new BigDecimal("100");

    private static final int CHUNK = 500;

    private final PublicApiClient client;
    private final PlaceRepository placeRepository;
    private final CongestionIngestWriter writer;

    public CongestionIngestService(PublicApiClient client,
                                   PlaceRepository placeRepository,
                                   CongestionIngestWriter writer) {
        this.client = client;
        this.placeRepository = placeRepository;
        this.writer = writer;
    }

    /**
     * 적재 결과. <b>매칭 실패 건수를 함께 돌려주는 것이 핵심이다</b> -
     * 나중에 출처가 이름을 바꿔 매칭률이 떨어져도 저장 자체는 성공하므로, 이 수치가 없으면
     * "왜 혼잡 정보가 줄었지"를 한참 뒤에야 알게 된다.
     */
    public record CongestionIngestResult(int fetched, int saved, int removedSameVersion,
                                         int matchedPlaces, int unmatchedNames, int ambiguousPlaces,
                                         int forecastDays, String baseAt) {
    }

    /** 이름 색인과 그 과정에서 제외된 건수. 배치 상태를 필드에 두지 않으려고 묶어서 돌려준다. */
    private record NameIndex(Map<String, Long> byName, int ambiguous) {
    }

    public CongestionIngestResult ingest() {
        List<CnctrRateItem> items = new ArrayList<>();
        for (String signgu : SIGNGU_CODES) {
            items.addAll(client.fetchAll(PATH,
                    Map.of("areaCd", JEJU, "signguCd", signgu),
                    new TypeReference<TourApiResponse<CnctrRateItem>>() {
                    }));
        }
        log.info("집중률 수신: {}행", items.size());

        NameIndex index = buildNameIndex();

        // 발표 버전 = 배치 실행일 00:00(제주 기준). API가 발표 시각을 주지 않아 우리가 정한다
        LocalDateTime baseAt = LocalDate.now(PlaceNameNormalizer.JEJU_ZONE).atStartOfDay();
        int removed = writer.clearVersion(baseAt);

        List<CongestionIngestWriter.Row> rows = new ArrayList<>();
        Set<String> matched = new HashSet<>();
        Set<String> unmatched = new HashSet<>();
        Set<LocalDate> days = new HashSet<>();

        for (CnctrRateItem item : items) {
            Long placeId = index.byName().get(normalize(item.tAtsNm()));
            if (placeId == null) {
                unmatched.add(item.tAtsNm());
                continue;
            }
            LocalDate day = parseDay(item.baseYmd());
            BigDecimal rate = parseRate(item.cnctrRate());
            if (day == null || rate == null) {
                continue;   // 값이 깨졌으면 행을 만들지 않는다 - 0으로 채우지 않는다(명세서 16.0)
            }
            matched.add(item.tAtsNm());
            days.add(day);
            rows.add(new CongestionIngestWriter.Row(
                    placeId, PlaceNameNormalizer.jejuDayToUtc(day), rate));
        }

        int saved = 0;
        for (int i = 0; i < rows.size(); i += CHUNK) {
            saved += writer.saveChunk(SOURCE_CODE, baseAt,
                    rows.subList(i, Math.min(i + CHUNK, rows.size())));
        }

        CongestionIngestResult result = new CongestionIngestResult(
                items.size(), saved, removed, matched.size(), unmatched.size(),
                index.ambiguous(), days.size(), baseAt.toString());
        log.info("집중률 적재 완료 {}", result);
        if (!unmatched.isEmpty()) {
            log.warn("이름 매칭 실패 {}곳 - 두 API가 같은 곳을 다르게 부르거나 KTO 목록에 없는 장소다. 예: {}",
                    unmatched.size(), unmatched.stream().limit(10).toList());
        }
        if (days.size() < 30) {
            log.info("예보 일수 {}일 - 화면 30일 캘린더의 나머지는 '정보 없음'이다(설계서 §3.5.2)", days.size());
        }
        return result;
    }

    // ── 이름 매칭 ─────────────────────────────────────────────────────

    /**
     * 정규화 이름 → place_id 색인.
     *
     * <p><b>이름이 겹치는 장소는 색인에서 뺀다.</b> 예를 들어 열안지오름은 봉개동과 오라동에
     * 하나씩 있는데 괄호를 걷어내면 이름이 같아진다 - 아무 쪽에나 붙이면
     * <b>틀린 혼잡도를 자신 있게 표시</b>하게 된다. 매칭을 포기하면 화면이 '정보 없음'이 되는데,
     * 그게 틀린 값보다 낫다(설계서 §1.2).
     */
    private NameIndex buildNameIndex() {
        Map<String, List<Long>> grouped = new HashMap<>();
        for (Object[] row : placeRepository.findIdAndNormalizedName()) {
            grouped.computeIfAbsent((String) row[1], k -> new ArrayList<>()).add((Long) row[0]);
        }
        Map<String, Long> byName = new HashMap<>();
        int ambiguous = 0;
        for (Map.Entry<String, List<Long>> entry : grouped.entrySet()) {
            if (entry.getValue().size() > 1) {
                ambiguous++;
                continue;
            }
            byName.put(entry.getKey(), entry.getValue().get(0));
        }
        log.info("이름 색인 {}건 (이름이 겹쳐 제외한 장소 {}건)", byName.size(), ambiguous);
        return new NameIndex(byName, ambiguous);
    }

    /** 규칙은 {@link PlaceNameNormalizer}가 단독으로 갖는다 - 여기 복붙하면 매칭이 조용히 깨진다. */
    private String normalize(String name) {
        return PlaceNameNormalizer.normalize(name);
    }

    // ── 변환 ─────────────────────────────────────────────────────────

    private LocalDate parseDay(String raw) {
        if (raw == null || raw.trim().length() != 8) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim(), YMD);
        } catch (Exception e) {
            return null;
        }
    }

    private BigDecimal parseRate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            BigDecimal rate = new BigDecimal(raw.trim());
            // CHECK(0~100)에 걸리기 전에 여기서 거른다 - 한 건 때문에 청크 500건이 롤백되지 않게
            if (rate.signum() < 0 || rate.compareTo(RATE_MAX) > 0) {
                log.warn("집중률 범위 이탈로 제외: {}", raw);
                return null;
            }
            return rate;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
