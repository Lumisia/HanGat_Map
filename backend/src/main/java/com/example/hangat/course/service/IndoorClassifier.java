package com.example.hangat.course.service;

import com.example.hangat.map.model.entity.Place;

import java.util.List;

/**
 * 실내/실외 분류 - 비 예보 날 실내 위주 코스를 만들기 위한 휴리스틱.
 *
 * <p><b>왜 컬럼이 아니라 코드인가</b>: places에 실내여부 컬럼이 없고(명세서 9.0),
 * 명세서에 없는 컬럼은 만들지 않는 관례다. 태그(place_tags)로 영속화하는 안은
 * 태그 도메인이 실제로 채워질 때 옮기기로 하고, v1은 이름 키워드로 판별한다.
 *
 * <p><b>휴리스틱임을 숨기지 않는다</b> - 화면 문구도 "실내 위주로 담았어요"까지만 말하고
 * 개별 장소의 실내 보장을 약속하지 않는다. 키워드는 KTO 관광지명 실데이터 기준으로 뽑았다.
 */
public final class IndoorClassifier {

    /** 이름에 이 단어가 있으면 실내로 본다. 애매하면 넣지 않는다 - 실외 오판(비 맞는 코스)이 더 나쁘다. */
    private static final List<String> INDOOR_KEYWORDS = List.of(
            "박물관", "미술관", "전시", "기념관", "체험관", "체험장", "아쿠아리움", "수족관",
            "카페", "공방", "온천", "사우나", "스파", "실내", "극장", "영화", "도서관",
            "갤러리", "과학관", "역사관"
    );

    private IndoorClassifier() {
    }

    public static boolean isIndoor(Place place) {
        return place != null && isIndoor(place.getName());
    }

    /**
     * 이름만 있는 호출자용(AI 후보 사실 {@code PlaceFact}, 응답 DTO). 같은 키워드 목록을 쓴다 -
     * 목록을 두 벌 두면 배치 코스와 AI 코스가 같은 장소를 다르게 판정한다.
     */
    public static boolean isIndoor(String name) {
        if (name == null) {
            return false;
        }
        return INDOOR_KEYWORDS.stream().anyMatch(name::contains);
    }
}
