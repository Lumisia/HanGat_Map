package com.example.hangat.review.controller;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.common.model.BaseResponse;
import com.example.hangat.common.model.BaseResponseStatus;
import com.example.hangat.common.model.PageResponse;
import com.example.hangat.review.model.ReviewCreateRequest;
import com.example.hangat.review.model.ReviewResponse;
import com.example.hangat.review.service.ReviewPhotoService;
import com.example.hangat.review.service.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 후기 API - 비회원 목록 조회와 회원의 작성·수정·삭제·사진 업로드를 제공한다.
 * 작성자 ID는 요청 본문이 아닌 JWT 인증 정보에서 가져온다.
 */
@Tag(name = "후기", description = "장소 방문 후기")
@RestController
@RequiredArgsConstructor
@Validated
public class ReviewController {

    /** 한 요청에 내어 주는 후기 상한. 화면은 10개씩 받으므로 정상 사용엔 영향이 없고, size=100000 같은 남용만 막는다. */
    static final int MAX_PAGE_SIZE = 20;
    /** size 를 안 보냈을 때. 지도 화면의 "더 보기" 단위(frontend ReviewApiService.PAGE_SIZE)와 같게 둔다. */
    static final String DEFAULT_PAGE_SIZE = "10";

    private final ReviewService reviewService;
    private final ReviewPhotoService photoService;

    // ────────────────────────── 공개 후기 목록 ──────────────────────────

    /**
     * 장소별 공개 후기를 페이지 단위로 반환한다.
     * page·size 범위 검증은 {@code @Validated}가 컨트롤러 진입 전에 하고, 위반은 GlobalExceptionHandler가 400 봉투로 바꾼다 -
     * 검증이 없으면 size=0·page=-1 이 PageRequest 예외로 봉투 밖 500 이 됐다.
     */
    @Operation(summary = "장소별 후기 목록",
            description = "삭제된 후기는 빠지고 최신순. 없는 장소면 PLACE_NOT_FOUND(3201). page는 0 이상, size는 1~20(기본 10, 넘으면 3000/400).")
    @GetMapping("/places/{placeId}/reviews")
    public BaseResponse<PageResponse<ReviewResponse>> getReviews(
            @PathVariable("placeId") Long placeId,
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "size", defaultValue = DEFAULT_PAGE_SIZE) @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        return BaseResponse.success(reviewService.getReviews(placeId, page, size));
    }

    // ────────────────────────── 회원 후기 작성 및 삭제 ──────────────────────────

    /** 인증된 작성자의 후기와 업로드된 사진 URL을 서비스에 전달한다. */
    @Operation(summary = "후기 작성", description = "회원 전용. 별점 또는 혼잡 제보 중 1개 필수, 한줄 60자, 사진 최대 5장.")
    @PostMapping("/places/{placeId}/reviews")
    public BaseResponse<ReviewResponse> create(@PathVariable("placeId") Long placeId,
                                               @RequestBody ReviewCreateRequest request,
                                               Authentication authentication) {
        return BaseResponse.success(reviewService.create(placeId, currentUserId(authentication), request));
    }

    /** 별점·제보·내용·유지할 사진 전체를 전달한다. 작성 후 7일 기한은 서버에서 검증한다. */
    @PutMapping("/reviews/{reviewId}")
    public BaseResponse<ReviewResponse> update(@PathVariable("reviewId") Long reviewId,
                                               @RequestBody ReviewCreateRequest request,
                                               Authentication authentication) {
        return BaseResponse.success(reviewService.update(reviewId, currentUserId(authentication), request));
    }

    /** 삭제 요청자의 신원을 확인하고 본인 여부 검사는 서비스에서 수행한다. */
    @Operation(summary = "후기 삭제", description = "작성자 본인만. 행은 남기고 상태만 DELETED 로 바꾼다.")
    @DeleteMapping("/reviews/{reviewId}")
    public BaseResponse<Void> delete(@PathVariable("reviewId") Long reviewId,
                                     Authentication authentication) {
        reviewService.delete(reviewId, currentUserId(authentication));
        return BaseResponse.success(null);
    }

    // ────────────────────────── 회원 사진 업로드 ──────────────────────────

    /** multipart 파일을 받고 후기 작성 요청에 재사용할 상대 URL 배열을 반환한다. */
    @Operation(summary = "후기 사진 업로드", description = "회원 전용. jpg/png/webp 최대 5장 - 돌려준 URL 을 작성 요청에 첨부한다.")
    @PostMapping("/reviews/photos")
    public BaseResponse<List<String>> uploadPhotos(
            @RequestParam("files") List<MultipartFile> files,
            Authentication authentication) {
        return BaseResponse.success(photoService.upload(files, currentUserId(authentication)));
    }

    // ────────────────────────── 인증 정보 확인 ──────────────────────────

    /** JWT 필터가 검증한 Long 사용자 ID만 사용하며, 인증 정보가 없으면 거절한다. */
    private Long currentUserId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Long userId)) {
            throw new BaseException(BaseResponseStatus.LOGIN_REQUIRED);
        }
        return userId;
    }
}
