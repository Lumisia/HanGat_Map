package com.example.hangat.review;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.common.model.BaseResponseStatus;
import com.example.hangat.common.model.PageResponse;
import com.example.hangat.map.model.entity.Place;
import com.example.hangat.map.model.entity.PlaceCategory;
import com.example.hangat.review.model.MyReviewResponse;
import com.example.hangat.review.model.Review;
import com.example.hangat.review.model.ReviewImage;
import com.example.hangat.review.model.ReviewStatus;
import com.example.hangat.review.repository.ReviewImageRepository;
import com.example.hangat.review.repository.ReviewRepository;
import com.example.hangat.review.service.MyReviewService;
import com.example.hangat.user.model.UserStatus;
import com.example.hangat.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 내 리뷰 목록 - 계정 확인, 정렬 화이트리스트, 사진 일괄 조회. DB 없이 리포지토리를 목으로 둔다.
 * (찜 쪽 {@code FavoriteServiceTest} 와 같은 방식이다 - 엔티티 빌더가 막혀 있어 목으로 만든다)
 */
class MyReviewServiceTest {

    private final ReviewRepository reviews = mock(ReviewRepository.class);
    private final ReviewImageRepository images = mock(ReviewImageRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final MyReviewService service = new MyReviewService(reviews, images, users);

    @Test
    void anonymousIsRejectedBeforeAnyQuery() {
        assertThatThrownBy(() -> service.list(null, 0, 10, "created_desc"))
                .isInstanceOf(BaseException.class)
                .satisfies(e -> assertThat(((BaseException) e).getStatus())
                        .isEqualTo(BaseResponseStatus.LOGIN_REQUIRED));

        verify(users, never()).existsByIdAndStatus(any(), any());
        verify(reviews, never()).findMyReviews(any(), any(), any(), any());
    }

    @Test
    void nonActiveAccountCannotReadItsReviews() {
        when(users.existsByIdAndStatus(1L, UserStatus.ACTIVE)).thenReturn(false);

        assertThatThrownBy(() -> service.list(1L, 0, 10, "created_desc"))
                .isInstanceOf(BaseException.class)
                .satisfies(e -> assertThat(((BaseException) e).getStatus())
                        .isEqualTo(BaseResponseStatus.ACCOUNT_SUSPENDED));

        verify(reviews, never()).findMyReviews(any(), any(), any(), any());
    }

    /** 정렬 값은 그대로 JPQL 로 들어가므로 화이트리스트 밖은 쿼리 전에 막는다. */
    @Test
    void unknownSortIsRejectedBeforeQuery() {
        active();

        assertThatThrownBy(() -> service.list(1L, 0, 10, "rating"))
                .isInstanceOf(BaseException.class)
                .satisfies(e -> assertThat(((BaseException) e).getStatus())
                        .isEqualTo(BaseResponseStatus.REQUEST_ERROR));

        verify(reviews, never()).findMyReviews(any(), any(), any(), any());
    }

    @Test
    void allowedSortsReachTheQueryWithActiveStatusOnly() {
        active();
        when(reviews.findMyReviews(any(), any(), any(), any())).thenReturn(Page.empty());

        for (String sort : List.of("created_desc", "rating_desc", "rating_asc")) {
            service.list(1L, 0, 10, sort);
            verify(reviews).findMyReviews(
                    eq(1L), eq(ReviewStatus.ACTIVE), eq(sort), eq(PageRequest.of(0, 10)));
        }
    }

    /** 빈 페이지에서 사진 조회까지 나가면 헛쿼리다. */
    @Test
    void emptyPageSkipsImageLookup() {
        active();
        when(reviews.findMyReviews(any(), any(), any(), any())).thenReturn(Page.empty());

        PageResponse<MyReviewResponse> result = service.list(1L, 0, 10, "created_desc");

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
        verify(images, never()).findByReviewIdInOrderBySortOrder(anyList());
    }

    /** 사진은 후기마다 부르지 않고 페이지 전체를 한 번에 읽어 후기별로 나눈다. */
    @Test
    void imagesAreFetchedOnceAndGroupedPerReview() {
        active();
        Review withPhotos = review(10L, (byte) 5, "좋았어요");
        Review withoutPhotos = review(11L, null, "제보만");
        when(reviews.findMyReviews(any(), any(), any(), any())).thenReturn(
                new PageImpl<>(List.of(withPhotos, withoutPhotos), PageRequest.of(0, 10), 2));
        // 사진 목도 thenReturn 인자 안에서 만들지 않는다(위 review 와 같은 이유).
        List<ReviewImage> photos = List.of(
                image(withPhotos, "/reviews/10/a.png"), image(withPhotos, "/reviews/10/b.png"));
        when(images.findByReviewIdInOrderBySortOrder(List.of(10L, 11L))).thenReturn(photos);

        List<MyReviewResponse> rows = service.list(1L, 0, 10, "created_desc").getContent();

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).reviewId()).isEqualTo(10L);
        assertThat(rows.get(0).imageUrls())
                .containsExactly("/reviews/10/a.png", "/reviews/10/b.png");
        assertThat(rows.get(1).imageUrls()).isEmpty();
        verify(images).findByReviewIdInOrderBySortOrder(anyList());
    }

    /** 별점 없는 제보 후기도 목록에 나온다 - 0 으로 바꾸지 않는다. */
    @Test
    void reportOnlyReviewKeepsNullRatingAndCongestion() {
        active();
        Review report = review(12L, null, null);
        when(reviews.findMyReviews(any(), any(), any(), any())).thenReturn(
                new PageImpl<>(List.of(report), PageRequest.of(0, 10), 1));
        when(images.findByReviewIdInOrderBySortOrder(anyList())).thenReturn(List.of());

        MyReviewResponse row = service.list(1L, 0, 10, "created_desc").getContent().get(0);

        assertThat(row.rating()).isNull();
        assertThat(row.congestionReport()).isNull();
        assertThat(row.placeId()).isEqualTo(7L);
        assertThat(row.placeName()).isEqualTo("금오름");
        assertThat(row.placeCategory()).isEqualTo("관광지");
    }

    private void active() {
        when(users.existsByIdAndStatus(1L, UserStatus.ACTIVE)).thenReturn(true);
    }

    private static Review review(Long id, Byte rating, String content) {
        // 장소 목을 먼저 완성한다 - when(...) 인자 안에서 다시 스터빙하면 Mockito 가 엉킨다.
        Place place = place();
        Review review = mock(Review.class);
        when(review.getId()).thenReturn(id);
        when(review.getPlace()).thenReturn(place);
        when(review.getRating()).thenReturn(rating);
        when(review.getContent()).thenReturn(content);
        when(review.getCreatedAt()).thenReturn(LocalDateTime.of(2026, 9, 1, 12, 0));
        when(review.getUpdatedAt()).thenReturn(LocalDateTime.of(2026, 9, 1, 12, 0));
        return review;
    }

    private static ReviewImage image(Review review, String url) {
        ReviewImage image = mock(ReviewImage.class);
        when(image.getReview()).thenReturn(review);
        when(image.getImageUrl()).thenReturn(url);
        return image;
    }

    private static Place place() {
        PlaceCategory category = mock(PlaceCategory.class);
        when(category.getName()).thenReturn("관광지");
        Place place = mock(Place.class);
        when(place.getId()).thenReturn(7L);
        when(place.getName()).thenReturn("금오름");
        when(place.getPrimaryCategory()).thenReturn(category);
        return place;
    }
}
