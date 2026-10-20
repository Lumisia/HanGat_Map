package com.example.hangat.map.hiddengem;

import com.example.hangat.common.util.DateTimes;
import com.example.hangat.map.model.entity.Place;
import com.example.hangat.map.model.entity.PlaceSourceMapping;
import com.example.hangat.map.repository.CongestionForecastRepository;
import com.example.hangat.map.repository.PlaceSourceMappingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 숨은 명소 점수·판정을 places 에 기록한다 (#숨은 명소). 규칙은 {@link HiddenGemRule} 한 곳에 있다.
 *
 * <p>언제 도는가: 혼잡(집중률) 적재 직후. "유명도 하위"가 최신 집중률 발표분의 집계 대상 여부로 정해지므로
 * 그 발표분이 없으면 판정할 수 없다 - 그때는 아무것도 바꾸지 않고 {@code skipped} 로 알린다.
 * 없는 값을 기준으로 전부 "숨은 명소"로 만드는 일이 없게.
 *
 * <p>멱등: 같은 날 다시 돌려도 같은 결과다. 값이 바뀐 장소만 다시 쓴다(계산 시각 = 마지막으로 값이 바뀐 때).
 */
@Service
public class HiddenGemScoringService {

    private static final Logger log = LoggerFactory.getLogger(HiddenGemScoringService.class);

    static final String KTO = "KTO";

    /**
     * 유명도 기준으로 삼기에 너무 얇은 명단은 쓰지 않는다. 집중률 API가 일부만 응답한 날(부분 수신)에 판정하면
     * 주요 관광지가 대거 "집계 대상 아님"으로 뒤집혀 숨은 명소가 된다. 실측 명단은 345곳이다.
     */
    static final int MIN_FAMOUS_COUNT = 250;

    private final HiddenGemRule rule;
    private final PlaceSourceMappingRepository mappingRepository;
    private final CongestionForecastRepository congestionRepository;

    public HiddenGemScoringService(HiddenGemRule rule,
                                   PlaceSourceMappingRepository mappingRepository,
                                   CongestionForecastRepository congestionRepository) {
        this.rule = rule;
        this.mappingRepository = mappingRepository;
        this.congestionRepository = congestionRepository;
    }

    /**
     * @param version     적용한 규칙 버전
     * @param skipped     판정을 건너뛰었는가(아무것도 바꾸지 않았다) - 발표분이 없거나 명단이 {@value #MIN_FAMOUS_COUNT}곳 미만
     * @param famousCount 유명도 기준으로 쓴 집계 대상 명단 크기(발표분에 예보가 있는 장소 수). 실측 345 근처여야 정상
     * @param evaluated   판정한 KTO 관광지 수(점수를 기록한 후보)
     * @param hiddenGems  판정 결과 숨은 명소인 수
     * @param famous      집계 대상(주요 관광지)이라 제외된 수
     * @param closed      폐업이라 제외된 수
     * @param changed     이번 실행으로 값이 바뀐 장소 수 - 매일 돌아도 대부분 0에 가깝다
     * @param baseAt      유명도 판정에 쓴 집중률 발표분
     */
    public record HiddenGemScoringResult(String version, boolean skipped, int famousCount, int evaluated,
                                         int hiddenGems, int famous, int closed, int changed, LocalDateTime baseAt) {
    }

    @Transactional
    public HiddenGemScoringResult score() {
        LocalDateTime baseAt = congestionRepository.findLatestBaseAt().orElse(null);
        if (baseAt == null) {
            log.warn("숨은 명소 판정 건너뜀 - 집중률 발표분이 없어 유명도를 가릴 수 없다");
            return new HiddenGemScoringResult(HiddenGemRule.VERSION, true, 0, 0, 0, 0, 0, 0, null);
        }
        Set<Long> famousIds = new HashSet<>(congestionRepository.findPlaceIdsOfVersion(baseAt));
        if (famousIds.size() < MIN_FAMOUS_COUNT) {
            log.warn("숨은 명소 판정 건너뜀 - 집계 대상 명단이 {}곳뿐이라(기준 {}) 부분 수신으로 본다 baseAt={}",
                    famousIds.size(), MIN_FAMOUS_COUNT, baseAt);
            return new HiddenGemScoringResult(HiddenGemRule.VERSION, true, famousIds.size(), 0, 0, 0, 0, 0, baseAt);
        }
        List<PlaceSourceMapping> mappings = mappingRepository.findAllBySourceCodeWithPlace(KTO);
        LocalDateTime now = DateTimes.nowUtc();

        int evaluated = 0;
        int gems = 0;
        int famous = 0;
        int closed = 0;
        int changed = 0;
        for (PlaceSourceMapping mapping : mappings) {
            if (!mapping.isActive()) continue;   // 출처에서 사라진 매핑은 판정하지 않는다
            Place place = mapping.getPlace();
            HiddenGemRule.Verdict verdict = rule.evaluate(place, famousIds.contains(place.getId()));
            if ("not-tourist".equals(verdict.reason())) continue;   // 음식점·숙소는 후보가 아니다 - 점수도 남기지 않는다
            evaluated++;
            if (verdict.hiddenGem()) gems++;
            if ("famous".equals(verdict.reason())) famous++;
            if ("closed".equals(verdict.reason())) closed++;
            // 바뀐 것만 쓴다 - 매일 2천 행의 updated_at 을 흔들면 "원천이 언제 바뀌었나"를 읽을 수 없다
            if (verdict.hiddenGem() != place.isHiddenGem()
                    || !HiddenGemRule.VERSION.equals(place.getHiddenGemAlgorithmVersion())
                    || place.getHiddenGemScore() == null
                    || place.getHiddenGemScore().compareTo(verdict.score()) != 0) {
                place.updateHiddenGem(verdict.score(), verdict.hiddenGem(), HiddenGemRule.VERSION, now);
                changed++;
            }
        }
        HiddenGemScoringResult result = new HiddenGemScoringResult(
                HiddenGemRule.VERSION, false, famousIds.size(), evaluated, gems, famous, closed, changed, baseAt);
        log.info("숨은 명소 판정 완료 {}", result);
        return result;
    }
}
