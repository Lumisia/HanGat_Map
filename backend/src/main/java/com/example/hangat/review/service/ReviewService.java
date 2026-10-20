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
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 후기 업무 처리 - 장소별 목록, 작성, 본인 수정·삭제와 평점 요약을 관리한다.
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
    private final EntityManager entityManager;

    // ────────────────────────── 후기 목록 조회 ──────────────────────────

    /** 장소별 후기 목록 - 삭제분 제외, 최신순 */
    @Transactional(readOnly = true)
    public PageResponse<ReviewResponse> getReviews(Long placeId, int page, int size) {
        if (!placeRepository.existsById(placeId)) {
            throw new BaseException(BaseResponseStatus.PLACE_NOT_FOUND);
        }
        Page<Review> reviews = reviewRepository.findByPlaceIdAndStatusOrderByCreatedAtDesc(
                placeId, ReviewStatus.ACTIVE, PageRequest.of(page, size));

        // 페이지(기본 10건, 최대 20건)의 사진을 쿼리 한 번으로 - 후기마다 조회하면 N+1
        List<Long> ids = reviews.getContent().stream().map(Review::getId).toList();
        Map<Long, List<ReviewImage>> imagesByReview = ids.isEmpty() ? Map.of()
                : imageRepository.findByReviewIdInOrderBySortOrder(ids).stream()
                        .collect(Collectors.groupingBy(i -> i.getReview().getId()));

        Map<Long, User> authors = authorsOf(
                reviews.getContent().stream().map(Review::getUserId).toList());
        return PageResponse.from(reviews.map(r ->
                ReviewResponse.from(r, imagesByReview.getOrDefault(r.getId(), List.of()),
                        authors.get(r.getUserId()))));
    }

    /** 닉네임과 현재 프로필 사진을 페이지당 쿼리 한 번(IN)으로 읽는다. 사용자 엔티티 자체는 응답하지 않는다. */
    private Map<Long, User> authorsOf(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
    }

    // ────────────────────────── 후기 작성 및 삭제 ──────────────────────────

    /** 본인이 업로드한 사진만 첨부하고 후기와 장소의 평점 요약을 함께 저장한다. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReviewResponse create(Long placeId, Long userId, ReviewCreateRequest req) {
        lockActiveUser(userId);
        Place place = placeForUpdate(placeId);
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
        return ReviewResponse.from(review, images, authorsOf(List.of(userId)).get(userId));
    }

    /** 최초 작성 후 7일 이내에만 수정한다. 기존 첨부는 유지하고 새 사진만 소유권을 검증한다. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReviewResponse update(Long reviewId, Long userId, ReviewCreateRequest req) {
        lockActiveUser(userId);
        Review review = ownedReviewForUpdate(reviewId, userId);
        Place place = placeForUpdate(review.getPlace().getId());
        assertEditable(review);
        validate(req);
        var report = parseReport(req.getCongestionReport());
        List<String> urls = req.getImageUrls() == null ? List.of() : req.getImageUrls();
        if (urls.stream().anyMatch(Objects::isNull) || new HashSet<>(urls).size() != urls.size()) {
            throw new BaseException(BaseResponseStatus.REQUEST_ERROR);
        }
        List<ReviewImage> previous = imageRepository.findByReviewIdOrderBySortOrder(reviewId);
        Map<String, ReviewImage> retained = previous.stream()
                .collect(Collectors.toMap(ReviewImage::getImageUrl, i -> i));
        boolean changed = !Objects.equals(review.getRating(), req.getRating())
                || !Objects.equals(review.getCongestionReport(), report)
                || !Objects.equals(Objects.toString(review.getContent(), ""), Objects.toString(req.getContent(), ""))
                || !previous.stream().map(ReviewImage::getImageUrl).toList().equals(urls);
        if (!changed) {
            return ReviewResponse.from(review, previous, authorsOf(List.of(userId)).get(userId));
        }

        // 현재 후기의 사진만 재사용할 수 있다. 다른 후기·다른 사용자의 사진은 검증에서 거절된다.
        var added = photoService.validateAttachments(
                urls.stream().filter(url -> !retained.containsKey(url)).toList(), userId).stream()
                .collect(Collectors.toMap(ReviewPhotoService.Attachment::url, a -> a));
        // 저장소 조회 중 기한이 지날 수도 있으므로 실제 변경 직전 다시 확인한다.
        LocalDateTime now = assertEditable(review);
        List<ReviewImage> removed = previous.stream().filter(i -> !urls.contains(i.getImageUrl())).toList();
        imageRepository.deleteAll(removed);
        List<ReviewImage> images = new ArrayList<>();
        for (int i = 0; i < urls.size(); i++) {
            String url = urls.get(i);
            ReviewImage image = retained.get(url);
            if (image != null) {
                image.reorder(i);
            } else {
                var attachment = added.get(url);
                image = imageRepository.save(ReviewImage.builder().review(review)
                        .storageKey(attachment.key()).imageUrl(attachment.url()).sortOrder(i).build());
            }
            images.add(image);
        }
        review.edit(req.getRating(), report, req.getContent(), now);
        refreshSummary(place);
        if (!removed.isEmpty()) {
            events.publishEvent(new ReviewPhotosDeleted(removed.stream().map(ReviewImage::getStorageKey).toList()));
        }
        return ReviewResponse.from(review, images, authorsOf(List.of(userId)).get(userId));
    }

    /** 클라이언트의 버튼 표시와 무관하게 서버 시각으로 기한을 강제한다. */
    private LocalDateTime assertEditable(Review review) {
        LocalDateTime now = LocalDateTime.now();
        if (!review.canEditAt(now)) {
            throw new BaseException(BaseResponseStatus.REVIEW_EDIT_EXPIRED);
        }
        return now;
    }

    /** 같은 후기의 편집과 삭제는 잠금 이후 상태·소유자를 검사한다. */
    private Review ownedReviewForUpdate(Long reviewId, Long userId) {
        Review review = reviewRepository.findForUpdate(reviewId)
                .orElseThrow(() -> new BaseException(BaseResponseStatus.REVIEW_NOT_FOUND));
        entityManager.refresh(review, LockModeType.PESSIMISTIC_WRITE);
        if (review.getStatus() != ReviewStatus.ACTIVE) {
            throw new BaseException(BaseResponseStatus.REVIEW_NOT_FOUND);
        }
        if (!review.getUserId().equals(userId)) {
            throw new BaseException(BaseResponseStatus.REVIEW_FORBIDDEN);
        }
        return review;
    }

    /** 본인 후기만 논리 삭제하고, 커밋이 성공한 경우에만 사진 정리를 진행한다. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(Long reviewId, Long userId) {
        lockActiveUser(userId);
        Review review = ownedReviewForUpdate(reviewId, userId);
        Place place = placeForUpdate(review.getPlace().getId());
        review.delete();
        refreshSummary(place);
        // 수신자는 AFTER_COMMIT이므로 DB 롤백 시 파일이 먼저 사라지지 않는다.
        events.publishEvent(new ReviewPhotosDeleted(
                imageRepository.findByReviewIdOrderBySortOrder(reviewId).stream()
                        .map(ReviewImage::getStorageKey).toList()
        ));
    }

    // ────────────────────────── 입력 검증 및 평점 집계 ──────────────────────────

    /** 인증 이후 탈퇴한 지연 요청도 차단한다. 잠금 순서는 회원 → 후기 → 장소다. */
    private void lockActiveUser(Long userId) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BaseException(BaseResponseStatus.USER_NOT_FOUND));
        // OSIV에 남은 엔티티는 잠금 조회만으로 최신 상태가 되지 않는다.
        entityManager.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        if (!user.getStatus().isLoginAllowed()) {
            throw new BaseException(user.getStatus().getLoginDeniedStatus());
        }
    }

    private Place placeForUpdate(Long placeId) {
        Place place = placeRepository.findByIdForUpdate(placeId)
                .orElseThrow(() -> new BaseException(BaseResponseStatus.PLACE_NOT_FOUND));
        entityManager.refresh(place, LockModeType.PESSIMISTIC_WRITE);
        return place;
    }

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
        // READ_COMMITTED에서 장소 잠금 이전에 확정된 다른 회원의 변경까지 집계한다.
        Object[] row = (Object[]) reviewRepository.summarize(place.getId())[0];
        Double avg = (Double) row[0];
        long count = (Long) row[1];
        place.updateReviewSummary(
                avg == null ? null : BigDecimal.valueOf(avg).setScale(2, RoundingMode.HALF_UP),
                (int) count);
    }
}
