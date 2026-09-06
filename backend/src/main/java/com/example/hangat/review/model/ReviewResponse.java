package com.example.hangat.review.model;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 후기 응답 - 작성 결과와 장소별 목록에서 같은 응답 형식을 사용한다.
 * 엔티티 대신 필요한 정보와 사진 URL만 전달하며 저장 키를 별도 필드로 반환하지 않는다.
 */
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ReviewResponse {

    private final Long id;
    private final Long userId;
    /** 작성자 닉네임. 탈퇴 등으로 유저가 없으면 null - 화면이 대체 표기한다 */
    private final String nickname;
    /** null = 별점 없이 혼잡 제보만 한 후기 */
    private final Byte rating;
    /** QUIET/NORMAL/CROWDED 또는 null */
    private final String congestionReport;
    private final String content;
    private final List<String> imageUrls;
    private final LocalDateTime createdAt;

    // ────────────────────────── 응답 변환 ──────────────────────────

    /** 조회한 사진 순서를 유지하고 닉네임·선택 입력의 null을 그대로 응답에 반영한다. */
    public static ReviewResponse from(Review review, List<ReviewImage> images, String nickname) {
        return ReviewResponse.builder()
                .id(review.getId())
                .userId(review.getUserId())
                .nickname(nickname)
                .rating(review.getRating())
                .congestionReport(review.getCongestionReport() == null
                        ? null : review.getCongestionReport().name())
                .content(review.getContent())
                .imageUrls(images.stream().map(ReviewImage::getImageUrl).toList())
                .createdAt(review.getCreatedAt())
                .build();
    }
}
