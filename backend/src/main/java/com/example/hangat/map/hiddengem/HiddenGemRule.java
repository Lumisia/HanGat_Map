package com.example.hangat.map.hiddengem;

import com.example.hangat.map.model.entity.Place;
import com.example.hangat.map.model.enums.BusinessStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 숨은 명소 판별 산식 - {@value #VERSION}. 심사 설명서에 그대로 공개하는 규칙이라 여기 한 곳에만 둔다.
 *
 * <p>요구사항표 정의: <b>TourAPI 등재 ∧ 콘텐츠 품질 충족 ∧ 유명도 하위</b>.
 *
 * <ul>
 *   <li><b>TourAPI 등재</b>: 호출부가 KTO 매핑이 있는 장소만 넘긴다. 이 클래스는 관광지(TOURIST) 카테고리만 후보로 본다 -
 *       음식점·숙소·쇼핑은 "명소"가 아니다.</li>
 *   <li><b>유명도 하위</b>: 한국관광공사가 집중률(방문자 집계)을 산출하는 관광지는 제주 448곳뿐이고, 이들이 곧 주요
 *       관광지다. 그 <b>집계 대상이 아닌</b> 등재 관광지를 "덜 알려진 곳"으로 본다. 집중률 값 자체는 장소별 최성수기
 *       대비 상대값이라 장소 간 유명도 비교에 쓰지 않는다(무명 오름 90이 성산일출봉 40보다 붐비는 게 아니다).
 *       <br>한계: 우리 DB의 집계 대상 명단은 집중률 API 이름을 KTO 장소에 매칭한 결과라 448곳 중 매칭에 성공한
 *       곳(실측 345곳, 약 77%)뿐이다. 매칭이 안 된 주요 관광지는 "집계 대상 아님"으로 보여 숨은 명소로 잘못 판정될 수
 *       있다 - 판정 결과는 명단 크기({@code famousCount})와 함께 기록·검토한다.</li>
 *   <li><b>콘텐츠 품질</b>: 소개할 만한 정보가 갖춰졌는지를 0~1 점수로 센다. 사진 0.30 · 좌표 0.10 · 주소 0.10 ·
 *       운영시간 0.20 · 소개글 0.20 · 후기 1건 이상 0.10. {@value #THRESHOLD_TEXT} 이상이면 충족 - 사진·좌표·주소에
 *       운영시간이나 소개글 중 하나는 있어야 넘는다.</li>
 * </ul>
 *
 * <p>정직성: 폐업(CLOSED)은 제외한다. 점수는 판정과 무관하게 관광지 후보 전부에 기록해 화면·설명서에서 근거를 보일 수 있게 한다.
 * 착한가격업소 배치가 소개글 자리에 넣는 메뉴 문단({@link Place#GOOD_PRICE_MENU_PREFIX})은 소개글로 치지 않는다.
 *
 * <p>정의상 숨은 명소는 집중률 예보가 없는 곳이다. 그래서 예보를 전제로 하는 추천(한산 장소 카드·대안·샘플 코스)에는
 * 오르지 않고, 지도 레이어·장소 상세 배지·별도 큐레이션으로 보여 준다.
 */
@Component
public class HiddenGemRule {

    /** places.hidden_gem_algorithm_version 에 기록한다. 규칙을 바꾸면 올린다 */
    public static final String VERSION = "HG_V1";

    static final String THRESHOLD_TEXT = "0.700";
    static final BigDecimal THRESHOLD = new BigDecimal(THRESHOLD_TEXT);

    static final BigDecimal PHOTO = new BigDecimal("0.30");
    static final BigDecimal COORDS = new BigDecimal("0.10");
    static final BigDecimal ADDRESS = new BigDecimal("0.10");
    static final BigDecimal HOURS = new BigDecimal("0.20");
    static final BigDecimal OVERVIEW = new BigDecimal("0.20");
    static final BigDecimal REVIEWS = new BigDecimal("0.10");

    static final String TOURIST_CATEGORY = "TOURIST";

    /**
     * @param score     콘텐츠 품질 점수(0~1, 소수 셋째 자리). 판정과 무관하게 항상 계산한다
     * @param hiddenGem 세 조건을 모두 만족하는가
     * @param reason    판정 근거 한 단어 - 로그·테스트용이며 저장하지 않는다
     */
    public record Verdict(BigDecimal score, boolean hiddenGem, String reason) {
    }

    /**
     * @param place  KTO 매핑이 있는 장소
     * @param famous 최신 집중률 발표분에 이 장소가 있는가(= 관광공사 집계 대상 = 주요 관광지)
     */
    public Verdict evaluate(Place place, boolean famous) {
        BigDecimal score = qualityScore(place);
        if (place.getBusinessStatus() == BusinessStatus.CLOSED) {
            return new Verdict(score, false, "closed");
        }
        if (place.getPrimaryCategory() == null || !TOURIST_CATEGORY.equals(place.getPrimaryCategory().getCode())) {
            return new Verdict(score, false, "not-tourist");
        }
        if (famous) {
            return new Verdict(score, false, "famous");
        }
        if (score.compareTo(THRESHOLD) < 0) {
            return new Verdict(score, false, "low-quality");
        }
        return new Verdict(score, true, "hidden-gem");
    }

    /** 있는 정보만 더한다. 빈 문자열은 없는 것으로 본다 - 공공 API는 빈 필드가 흔하다 */
    BigDecimal qualityScore(Place place) {
        BigDecimal score = BigDecimal.ZERO;
        if (present(place.getImageUrl())) score = score.add(PHOTO);
        if (place.getLatitude() != null && place.getLongitude() != null) score = score.add(COORDS);
        if (present(place.getRoadAddress()) || present(place.getLotAddress())) score = score.add(ADDRESS);
        if (present(place.getOperatingHoursText())) score = score.add(HOURS);
        if (present(place.getOverview()) && !place.getOverview().startsWith(Place.GOOD_PRICE_MENU_PREFIX)) {
            score = score.add(OVERVIEW);
        }
        if (place.getReviewCount() > 0) score = score.add(REVIEWS);
        return score.setScale(3, RoundingMode.HALF_UP);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
