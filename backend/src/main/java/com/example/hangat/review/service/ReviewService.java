package com.example.hangat.review.service;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.common.model.BaseResponseStatus;
import com.example.hangat.common.model.PageResponse;
import com.example.hangat.map.model.entity.Place;
import com.example.hangat.review.model.Review;
import com.example.hangat.review.model.ReviewCreateRequest;
import com.example.hangat.review.model.ReviewImage;
import com.example.hangat.review.model.ReviewPhotosDeleted;
import com.example.hangat.review.model.ReviewResponse;
import com.example.hangat.map.model.enums.CongestionLevel;
import com.example.hangat.review.model.ReviewStatus;
import com.example.hangat.map.repository.PlaceRepository;
import com.example.hangat.review.repository.ReviewImageRepository;
import com.example.hangat.review.repository.ReviewRepository;
import com.example.hangat.user.model.User;
import com.example.hangat.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 후기 업무 처리 - 장소별 목록, 작성, 본인 삭제와 평점 요약을 관리한다.
 * 사진 검증은 사진 서비스에 맡기고, DB 삭제 확정 후 파일 정리 이벤트를 전달한다.
 */
@Service
@RequiredArgsConstructor
public class ReviewService {

    public static final int MAX_IMAGES = 5;
    private static final int MAX_CONTENT = 60;

    private final ReviewRepository reviewRepository;
    private final ReviewImageRepository imageRepository;
    private final PlaceRepository placeRepository;
    private final UserRepository userRepository;
    private final ReviewPhotoService photoService;
    private final ApplicationEventPublisher events;

    // ────────────────────────── 후기 목록 조회 ──────────────────────────

    /** 장소별 후기 목록 - 삭제분 제외, 최신순 */
    @Transactional(readOnly = true)
    public PageResponse<ReviewResponse> getReviews(Long placeId, int page, int size) {
        if (!placeRepository.existsById(placeId)) {
            throw new BaseException(BaseResponseStatus.PLACE_NOT_FOUND);
        }
        Page<Review> reviews = reviewRepository.findByPlaceIdAndStatusOrderByCreatedAtDesc(
                placeId, ReviewStatus.ACTIVE, PageRequest.of(page, size));

        // 페이지(기본 6건)의 사진을 쿼리 한 번으로 - 후기마다 조회하면 N+1
        List<Long> ids = reviews.getContent().stream().map(Review::getId).toList();
        Map<Long, List<ReviewImage>> imagesByReview = ids.isEmpty() ? Map.of()
                : imageRepository.findByReviewIdInOrderBySortOrder(ids).stream()
                        .collect(Collectors.groupingBy(i -> i.getReview().getId()));

        Map<Long, String> nicknames = nicknamesOf(
                reviews.getContent().stream().map(Review::getUserId).toList());
        return PageResponse.from(reviews.map(r ->
                ReviewResponse.from(r, imagesByReview.getOrDefault(r.getId(), List.of()),
                        nicknames.get(r.getUserId()))));
    }

    /** 작성자 닉네임 - 페이지당 쿼리 한 번(IN). 탈퇴 등으로 유저가 없으면 맵에서 빠져 null 로 내려간다 */
    private Map<Long, String> nicknamesOf(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(userIds).stream()
                .filter(u -> u.getNickname() != null)
                .collect(Collectors.toMap(User::getId, User::getNickname, (a, b) -> a));
    }

    // ────────────────────────── 후기 작성 및 삭제 ──────────────────────────

    /** 본인이 업로드한 사진만 첨부하고 후기와 장소의 평점 요약을 함께 저장한다. */
    @Transactional
    public ReviewResponse create(Long placeId, Long userId, ReviewCreateRequest req) {
        Place place = placeRepository.findById(placeId)
                .orElseThrow(() -> new BaseException(BaseResponseStatus.PLACE_NOT_FOUND));
        validate(req);

        var attachments = photoService.validateAttachments(req.getImageUrls(), userId);

        Review review = reviewRepository.save(Review.builder()
                .userId(userId)
                .place(place)
                .rating(req.getRating())
                .congestionReport(parseReport(req.getCongestionReport()))
                .content(req.getContent())
                .status(ReviewStatus.ACTIVE)
                .build());

        List<ReviewImage> images = new ArrayList<>();
        for (int i = 0; i < attachments.size(); i++) {
            var attachment = attachments.get(i);
            images.add(imageRepository.save(ReviewImage.builder()
                    .review(review)
                    // 소유자 경로를 포함한 전체 키를 유지해야 조회·삭제 대상을 정확히 찾는다.
                    .storageKey(attachment.key())
                    .imageUrl(attachment.url())
                    .sortOrder(i)
                    .build()));
        }

        refreshSummary(place);
        return ReviewResponse.from(review, images, nicknamesOf(List.of(userId)).get(userId));
    }

    /** 본인 후기만 논리 삭제하고, 커밋이 성공한 경우에만 사진 정리를 진행한다. */
    @Transactional
    public void delete(Long reviewId, Long userId) {
        Review review = reviewRepository.findById(reviewId)
                .filter(r -> r.getStatus() == ReviewStatus.ACTIVE)
                .orElseThrow(() -> new BaseException(BaseResponseStatus.REVIEW_NOT_FOUND));
        // 본인 확인 - 다른 사람 후기 id 를 넣어 지우는 것을 막는다
        if (!review.getUserId().equals(userId)) {
            throw new BaseException(BaseResponseStatus.REVIEW_FORBIDDEN);
        }
        review.delete();
        refreshSummary(review.getPlace());
        // 수신자는 AFTER_COMMIT이므로 DB 롤백 시 파일이 먼저 사라지지 않는다.
        events.publishEvent(new ReviewPhotosDeleted(
                imageRepository.findByReviewIdOrderBySortOrder(reviewId).stream()
                        .map(ReviewImage::getStorageKey).toList()
        ));
    }

    // ────────────────────────── 입력 검증 및 평점 집계 ──────────────────────────

    /** 별점/제보 중 하나는 필수이며 한줄평 길이와 사진 개수를 제한한다. */
    private void validate(ReviewCreateRequest req) {
        boolean noRating = req.getRating() == null;
        boolean noReport = req.getCongestionReport() == null || req.getCongestionReport().isBlank();
        if (noRating && noReport) {
            throw new BaseException(BaseResponseStatus.REVIEW_RATING_OR_REPORT_REQUIRED);
        }
        if (!noRating && (req.getRating() < 1 || req.getRating() > 5)) {
            throw new BaseException(BaseResponseStatus.REQUEST_ERROR);
        }
        if (req.getContent() != null && req.getContent().length() > MAX_CONTENT) {
            throw new BaseException(BaseResponseStatus.REQUEST_ERROR);
        }
        if (req.getImageUrls() != null && req.getImageUrls().size() > MAX_IMAGES) {
            throw new BaseException(BaseResponseStatus.REVIEW_TOO_MANY_IMAGES);
        }
    }

    /** 생략된 제보는 null로 두고 등록된 혼잡 단계 이외의 문자열은 거절한다. */
    private CongestionLevel parseReport(String report) {
        if (report == null || report.isBlank()) {
            return null;
        }
        try {
            return CongestionLevel.valueOf(report);
        } catch (IllegalArgumentException e) {
            throw new BaseException(BaseResponseStatus.REQUEST_ERROR);
        }
    }

    /** places 의 평점 요약(비정규화) 갱신 - 별점 없는 후기는 평균에서 빠지고 건수에는 들어간다 */
    private void refreshSummary(Place place) {
        Object[] row = (Object[]) reviewRepository.summarize(place.getId())[0];
        Double avg = (Double) row[0];
        long count = (Long) row[1];
        place.updateReviewSummary(
                avg == null ? null : BigDecimal.valueOf(avg).setScale(2, RoundingMode.HALF_UP),
                (int) count);
    }
}
