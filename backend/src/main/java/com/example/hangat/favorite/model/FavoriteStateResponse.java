package com.example.hangat.favorite.model;

/**
 * 찜/해제 결과 - 요청 뒤의 상태를 그대로 말한다.
 * PUT 은 이미 찜돼 있어도 {@code favorited=true}, DELETE 는 없던 것을 지워도 {@code favorited=false} -
 * 화면은 이 값으로 하트를 맞추면 되고 "이미 찜한 장소입니다" 같은 실패를 다룰 필요가 없다.
 */
public record FavoriteStateResponse(Long placeId, boolean favorited) {
}
