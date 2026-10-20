package com.example.hangat.review.service;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.common.model.BaseResponseStatus;
import com.example.hangat.common.model.PageResponse;
import com.example.hangat.review.model.MyReviewResponse;
import com.example.hangat.review.model.Review;
import com.example.hangat.review.model.ReviewImage;
import com.example.hangat.review.model.ReviewStatus;
import com.example.hangat.review.repository.ReviewImageRepository;
import com.example.hangat.review.repository.ReviewRepository;
import com.example.hangat.user.model.UserStatus;
import com.example.hangat.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 본인이 작성한 리뷰 목록.
 * 작성·삭제·사진 정리는 기존 ReviewService와 ReviewPhotoService를 사용한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MyReviewService {

    private static final Set<String> SORTS =
            Set.of("created_desc", "rating_desc", "rating_asc");

    private final ReviewRepository reviews;
    private final ReviewImageRepository images;
    private final UserRepository users;

    public PageResponse<MyReviewResponse> list(
            Long userId, int page, int size, String sort
    ) {
        if (userId == null) {
            throw new BaseException(BaseResponseStatus.LOGIN_REQUIRED);
        }
        if (!users.existsByIdAndStatus(userId, UserStatus.ACTIVE)) {
            throw new BaseException(BaseResponseStatus.ACCOUNT_SUSPENDED);
        }
        if (!SORTS.contains(sort)) {
            throw new BaseException(BaseResponseStatus.REQUEST_ERROR);
        }

        Page<Review> result = reviews.findMyReviews(
                userId,
                ReviewStatus.ACTIVE,
                sort,
                PageRequest.of(page, size)
        );

        List<Long> ids = result.getContent().stream()
                .map(Review::getId)
                .toList();

        // 후기별로 사진 쿼리를 반복하지 않고 현재 페이지 사진을 한 번에 읽는다.
        Map<Long, List<ReviewImage>> grouped = ids.isEmpty()
                ? Map.of()
                : images.findByReviewIdInOrderBySortOrder(ids).stream()
                .collect(Collectors.groupingBy(
                        image -> image.getReview().getId()
                ));

        return PageResponse.from(result.map(review ->
                MyReviewResponse.from(
                        review,
                        grouped.getOrDefault(review.getId(), List.of())
                )
        ));
    }
}
