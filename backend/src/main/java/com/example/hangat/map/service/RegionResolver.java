package com.example.hangat.map.service;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 주소 → 제주 4권역 판정 - 설계서 §5.1
 *
 * <p><b>좌표가 아니라 행정구역으로 나눈다.</b> 제주 관광의 동/서/남/북 구분은 이미 관용적으로
 * 읍면동 단위로 정해져 있고, KTO가 주소에 읍면동을 담아 준다. 좌표로 선을 그으면
 * "왜 하필 그 선이냐"를 방어해야 하지만, 행정구역 기준이면 설명이 끝난다.
 *
 * <p>실측 정확도: KTO 제주 2,147건 중 <b>2,136건 판정 성공(99.5%)</b>.
 * 실패 11건은 추자도 8 + 원본 주소 손상 2 + 광역 항목 1건이다(2026-08-23).
 *
 * <p>추자도를 어느 권역에도 넣지 않는 이유: 제주 본섬에서 배로 1시간 거리라
 * 당일 코스 서비스 범위 밖이다. 우도는 본섬 접근성이 좋아 동부로 묶는다(관행).
 */
@Component
public class RegionResolver {

    private static final List<String> EAST = List.of("조천읍", "구좌읍", "성산읍", "표선면", "남원읍", "우도면");
    private static final List<String> WEST = List.of("애월읍", "한림읍", "한경면", "대정읍", "안덕면");

    /** 본섬 밖이라 어느 권역에도 넣지 않는다. */
    private static final String OUT_OF_SCOPE = "추자면";

    /**
     * 주소에서 권역 코드를 판정한다.
     *
     * @return NORTH / EAST / SOUTH / WEST, 판정 불가면 {@code null}
     */
    public String resolve(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        if (address.contains(OUT_OF_SCOPE)) {
            return null;
        }
        for (String eup : EAST) {
            if (address.contains(eup)) {
                return "EAST";
            }
        }
        for (String eup : WEST) {
            if (address.contains(eup)) {
                return "WEST";
            }
        }
        // 읍·면이 안 걸렸으면 동(洞) 지역이다. 시로 갈린다
        if (address.contains("서귀포시")) {
            return "SOUTH";
        }
        if (address.contains("제주시")) {
            return "NORTH";
        }
        // "제주특별자치도"까지만 있는 광역 항목 등
        return null;
    }

    /** Kakao coord2regioncode의 공식 행정구역 필드만으로 기존 4권역을 판정한다. */
    public String resolveAdministrativeRegion(String firstDepth, String secondDepth, String thirdDepth) {
        if (!"제주특별자치도".equals(firstDepth)) {
            return null;
        }
        return resolve(String.join(" ",
                firstDepth == null ? "" : firstDepth,
                secondDepth == null ? "" : secondDepth,
                thirdDepth == null ? "" : thirdDepth));
    }
}
