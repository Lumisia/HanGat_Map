package com.example.hangat.review.repository;

import com.example.hangat.review.model.Review;
import com.example.hangat.review.model.ReviewStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 후기 조회와 평점 집계.
 * 공개 장소 목록과 본인 목록 모두 삭제된 후기를 제외한다.
 */
public interface ReviewRepository extends JpaRepository<Review, Long> {

    /** 같은 후기의 수정·삭제를 직렬화해 사진 연결과 상태 변경이 엇갈리지 않게 한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Review r where r.id = :id")
    Optional<Review> findForUpdate(@Param("id") Long id);

    Page<Review> findByPlaceIdAndStatusOrderByCreatedAtDesc(
            Long placeId, ReviewStatus status, Pageable pageable
    );

    /** 기존 장소 평점 집계. 별점 없는 제보는 평균에서 제외한다. */
    @Query("""
            select avg(r.rating), count(r)
            from Review r
            where r.place.id = :placeId
              and r.status = 'ACTIVE'
            """)
    Object[] summarize(@Param("placeId") Long placeId);

    /**
     * 내 리뷰 목록.
     * 별점순 정렬에서 별점 없는 제보는 마지막에 놓고,
     * 같은 값이면 작성일·ID로 순서를 고정한다.
     */
    @EntityGraph(attributePaths = {"place", "place.primaryCategory"})
    @Query(
            value = """
                    select r
                    from Review r
                    where r.userId = :userId
                      and r.status = :status
                    order by
                      case
                        when :sort <> 'created_desc' and r.rating is null
                        then 1 else 0
                      end asc,
                      case when :sort = 'rating_desc' then r.rating else null end desc,
                      case when :sort = 'rating_asc' then r.rating else null end asc,
                      r.createdAt desc,
                      r.id desc
                    """,
            countQuery = """
                    select count(r)
                    from Review r
                    where r.userId = :userId
                      and r.status = :status
                    """
    )
    Page<Review> findMyReviews(
            @Param("userId") Long userId,
            @Param("status") ReviewStatus status,
            @Param("sort") String sort,
            Pageable pageable
    );
}
