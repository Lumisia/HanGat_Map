package com.example.hangat.map.place;

import com.example.hangat.map.model.entity.PlaceSourceMapping;
import com.example.hangat.map.model.enums.BusinessStatus;
import com.example.hangat.map.repository.PlaceSourceMappingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 출석 체크 - 적재가 끝난 뒤 "지난번엔 있었는데 이번 목록엔 없는" 장소를 찾는다.
 *
 * <p>공공 API는 폐업을 알려주지 않고 목록에서 빼기만 한다. 그래서 이번 적재에서 안 보인 매핑을
 * 비활성으로 두고(1회), 다른 날의 적재에서도 안 보이면(2회 연속) 장소를 CLOSED로 바꾼다.
 * 한 번에 폐업 처리하지 않는 이유: API가 하루 흔들려 몇 건 빠지면 멀쩡한 장소가 지도에서 사라진다.
 * CLOSED가 되면 목록·검색·코스 후보 쿼리가 알아서 거른다({@code PlaceRepository.LIST_SELECT}).
 * 행은 지우지 않는다 - 찜·후기·코스가 참조하고, 다시 나타나면 되살린다.
 *
 * <p>수신 건수가 활성 매핑의 {@link #MIN_COVERAGE} 미만이면 그날은 판정하지 않는다 -
 * 반쯤 실패한 응답으로 수천 곳에 한꺼번에 스트라이크를 주는 사고를 막는다.
 * "연속"은 날짜 기준이다 - 비활성이 된 날(매핑 updated_at)이 오늘이면 두 번째 결석으로 치지 않고 보류한다.
 * 실행 회차 기준으로 세면 같은 새벽에 Job이 뒷단계(SBIZ·착한가격)에서 실패해 재시도될 때
 * 하루 유예 없이 폐업이 됐다(최종점검 #62). 비활성 매핑은 되살아나기 전까지 아무도 갱신하지 않으므로
 * updated_at 이 곧 비활성이 된 시각이다.
 */
@Component
public class PlacePresenceReconciler {

    private static final Logger log = LoggerFactory.getLogger(PlacePresenceReconciler.class);

    /** "장소가 존재한다"의 근거가 되는 출처. 착한가격 CSV는 지정 현황이지 존재 목록이 아니라서 뺀다. */
    static final Set<String> PRESENCE_SOURCES = Set.of("KTO", "SBIZ");
    static final double MIN_COVERAGE = 0.8;

    private final PlaceSourceMappingRepository mappingRepository;

    public PlacePresenceReconciler(PlaceSourceMappingRepository mappingRepository) {
        this.mappingRepository = mappingRepository;
    }

    /**
     * skipped=true 면 수신이 부족해 판정하지 않은 것 - 배치는 이 경우를 실패로 기록한다.
     * deferred 는 안 보였지만 오늘 비활성이 된 매핑(같은 날 재실행) - 폐업을 다음 날로 미룬 수.
     */
    public record Result(int seen, int revived, int struck, int closed, int deferred, boolean skipped) {
        static Result skipped(int seen) {
            return new Result(seen, 0, 0, 0, 0, true);
        }
    }

    /**
     * @param sourceCode    KTO / SBIZ
     * @param seenSourceIds 이번 응답에 있던 출처 ID 전부 - 권역·카테고리로 걸러지기 <b>전</b> 값이어야 한다
     */
    @Transactional
    public Result reconcile(String sourceCode, Set<String> seenSourceIds) {
        List<Object[]> rows = mappingRepository.findPresenceRows(sourceCode);
        long active = rows.stream().filter(r -> (Boolean) r[2]).count();
        if (active > 0 && seenSourceIds.size() < active * MIN_COVERAGE) {
            log.warn("{} 출석 체크 건너뜀 - 수신 {}건이 활성 매핑 {}건의 {}% 미만(응답 불완전 의심)",
                    sourceCode, seenSourceIds.size(), active, (int) (MIN_COVERAGE * 100));
            return Result.skipped(seenSourceIds.size());
        }

        LocalDate today = LocalDate.now();
        List<Long> revive = new ArrayList<>();      // 보임 + 비활성
        List<Long> firstMiss = new ArrayList<>();   // 안 보임 + 활성
        List<Long> secondMiss = new ArrayList<>();  // 안 보임 + 어제 이전에 비활성
        int deferred = 0;                           // 안 보임 + 오늘 비활성(같은 날 재실행) - 보류
        for (Object[] r : rows) {
            boolean seen = seenSourceIds.contains((String) r[1]);
            boolean isActive = (Boolean) r[2];
            if (seen && !isActive) {
                revive.add((Long) r[0]);
            } else if (!seen && isActive) {
                firstMiss.add((Long) r[0]);
            } else if (!seen) {
                LocalDate struckOn = ((LocalDateTime) r[3]).toLocalDate();
                if (struckOn.isBefore(today)) {
                    secondMiss.add((Long) r[0]);
                } else {
                    deferred++;
                }
            }
        }

        for (PlaceSourceMapping m : load(revive)) {
            m.activate();
            m.getPlace().reopenIfClosed();
        }
        for (PlaceSourceMapping m : load(firstMiss)) {
            m.deactivate();
        }

        int closed = 0;
        List<PlaceSourceMapping> second = load(secondMiss);
        Set<Long> stillSeen = stillSeenElsewhere(second);
        for (PlaceSourceMapping m : second) {
            if (stillSeen.contains(m.getPlace().getId())) {
                continue;   // 다른 출처가 아직 보고 있다
            }
            if (m.getPlace().getBusinessStatus() == BusinessStatus.CLOSED) {
                continue;   // 이미 처리된 장소는 다시 세지 않는다
            }
            m.getPlace().markClosed();
            closed++;
        }

        Result result = new Result(seenSourceIds.size(), revive.size(), firstMiss.size(), closed, deferred, false);
        log.info("{} 출석 체크 {}", sourceCode, result);
        return result;
    }

    private List<PlaceSourceMapping> load(Collection<Long> ids) {
        return ids.isEmpty() ? List.of() : mappingRepository.findAllWithPlaceByIdIn(ids);
    }

    private Set<Long> stillSeenElsewhere(List<PlaceSourceMapping> mappings) {
        if (mappings.isEmpty()) {
            return Set.of();
        }
        List<Long> placeIds = mappings.stream().map(m -> m.getPlace().getId()).toList();
        return new HashSet<>(mappingRepository.findPlaceIdsStillSeenBy(placeIds, PRESENCE_SOURCES));
    }
}
