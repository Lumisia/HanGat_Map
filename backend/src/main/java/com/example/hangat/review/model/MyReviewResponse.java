package com.example.hangat.review.model;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.util.List;

/**
 * 마이페이지용 내 리뷰 응답.
 * 장소 이동에 필요한 실제 placeId와 첨부 사진 주소를 함께 반환한다.
 */
public record MyReviewResponse(
        Long reviewId,
        Long placeId,
        String placeName,
        String placeCategory,
        Byte rating,
        String congestionReport,
        String content,
        List<String> imageUrls,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime editedAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING) OffsetDateTime editableUntil
) {

    public static MyReviewResponse from(
            Review review, List<ReviewImage> images
    ) {
        return new MyReviewResponse(
                review.getId(),
                review.getPlace().getId(),
                review.getPlace().getName(),
                review.getPlace().getPrimaryCategory().getName(),
                review.getRating(),
                review.getCongestionReport() == null
                        ? null : review.getCongestionReport().name(),
                review.getContent(),
                images.stream().map(ReviewImage::getImageUrl).toList(),
                review.getCreatedAt(),
                review.getUpdatedAt(),
                review.getEditedAt(),
                review.editableUntil()
        );
    }
}
