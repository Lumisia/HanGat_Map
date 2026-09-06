package com.example.hangat.map.detail.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * KTO {@code detailIntro2} 응답 - 타입마다 필드명이 다르다.
 *
 * <p>한 레코드에 모든 타입의 필드를 담고, 실제로 값이 오는 건 해당 타입의 것뿐이다.
 * 어느 필드를 읽을지는 {@link com.example.hangat.map.detail.DetailFieldMapper}가 정한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlaceIntroItem(

        String contentid,
        String contenttypeid,

        // ── 12 관광지 ──
        String usetime,
        String restdate,
        String parking,

        // ── 14 문화시설 ──
        String usetimeculture,
        String restdateculture,
        String parkingfee,
        /** ★ 입장료. 실측상 이 타입에만 있다. "1,500원"이 아니라 대상별 요금표가 통째로 온다. */
        String usefee,

        // ── 15 축제·공연 ──
        /** ★ 이름은 '시간'이지만 값은 <b>이용요금</b>이다. 축제의 시간은 {@link #playtime}. */
        String usetimefestival,
        String playtime,

        // ── 28 레포츠 ──
        String usetimeleports,
        String restdateleports,
        String parkingleports,

        // ── 32 숙박 ──
        String checkintime,
        String checkouttime,

        // ── 38 쇼핑 ──
        String opentime,
        String restdateshopping,
        String parkingshopping,
        String restroom,

        // ── 39 음식점 ──
        String opentimefood,
        String restdatefood,
        String parkingfood,
        /** 대표 메뉴. 가격은 없다 - KTO가 메뉴명만 준다 */
        String firstmenu,
        /** 취급 메뉴. ","나 "/"로 여러 개가 온다 */
        String treatmenu
) {
}
