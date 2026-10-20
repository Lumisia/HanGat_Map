package com.example.hangat.favorite.controller;

import com.example.hangat.common.model.BaseResponse;
import com.example.hangat.favorite.model.FavoriteResponse;
import com.example.hangat.favorite.model.FavoriteStateResponse;
import com.example.hangat.favorite.service.FavoriteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 찜 API - 전부 회원 전용. 경로에 회원 ID가 없다: 항상 "내 찜"이고 회원은 JWT 인증 정보에서 온다.
 * 비로그인 요청은 SecurityConfig의 {@code anyRequest().authenticated()}가 401로 끊는다.
 */
@Tag(name = "찜", description = "회원의 장소 찜 - 지도 하트(MAP_009)와 마이페이지 찜 목록(MY_006)")
@RestController
@RequestMapping("/favorites")
public class FavoriteController {

    private final FavoriteService favoriteService;

    public FavoriteController(FavoriteService favoriteService) {
        this.favoriteService = favoriteService;
    }

    @Operation(summary = "내 찜 목록", description = "최근 찜한 순. 장소 요약 + 세부분류 + 대표사진 + 오늘 집중률(없으면 null).")
    @GetMapping
    public BaseResponse<List<FavoriteResponse>> list(@AuthenticationPrincipal Long userId) {
        return BaseResponse.success(favoriteService.list(userId));
    }

    @Operation(summary = "내 찜 장소 ID 목록", description = "지도 하트 상태를 맞추는 용도. 목록 응답보다 훨씬 가볍다.")
    @GetMapping("/ids")
    public BaseResponse<List<Long>> ids(@AuthenticationPrincipal Long userId) {
        return BaseResponse.success(favoriteService.placeIds(userId));
    }

    @Operation(summary = "찜", description = "멱등 - 이미 찜한 장소여도 성공(favorited=true). 없는 장소면 PLACE_NOT_FOUND(3201).")
    @PutMapping("/{placeId}")
    public BaseResponse<FavoriteStateResponse> add(@AuthenticationPrincipal Long userId,
                                                   @PathVariable("placeId") Long placeId) {
        return BaseResponse.success(favoriteService.add(userId, placeId));
    }

    @Operation(summary = "찜 해제", description = "멱등 - 찜한 적 없는 장소여도 성공(favorited=false).")
    @DeleteMapping("/{placeId}")
    public BaseResponse<FavoriteStateResponse> remove(@AuthenticationPrincipal Long userId,
                                                      @PathVariable("placeId") Long placeId) {
        return BaseResponse.success(favoriteService.remove(userId, placeId));
    }
}
