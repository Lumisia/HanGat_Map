package com.example.hangat.map.detail.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * KTO {@code detailCommon2} 응답 중 소개글 적재가 쓰는 부분.
 * 좌표·주소 같은 나머지는 목록 적재({@code areaBasedList2})가 이미 가지고 있어 여기서 읽지 않는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlaceCommonItem(
        String contentid,
        String title,
        /** 소개 원문. {@code <br>}·HTML 태그·엔티티가 섞여 온다 - 저장 전에 정리한다 */
        String overview
) {
}
