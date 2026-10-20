package com.example.hangat.review.controller;

import com.example.hangat.common.model.BaseResponse;
import com.example.hangat.common.model.PageResponse;
import com.example.hangat.review.model.MyReviewResponse;
import com.example.hangat.review.service.MyReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@Validated
@RequestMapping("/users/me/reviews")
@Tag(name = "내 리뷰 목록")
public class MyReviewController {

    private final MyReviewService service;

    @GetMapping
    @Operation(summary = "내가 작성한 리뷰 목록")
    public BaseResponse<PageResponse<MyReviewResponse>> list(
            @AuthenticationPrincipal Long userId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size,
            @RequestParam(defaultValue = "created_desc") String sort
    ) {
        return BaseResponse.success(service.list(userId, page, size, sort));
    }
}
