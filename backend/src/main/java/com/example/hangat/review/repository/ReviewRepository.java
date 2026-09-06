package com.example.hangat.review.repository;

import com.example.hangat.review.model.Review;
import com.example.hangat.review.model.ReviewStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 후기 DB 조회 - 장소별 목록과 평점 집계를 담당한다.
 * 목록과 집계에서 논리 삭제된 후기는 제외한다.
 */
public interface ReviewRepository extends JpaRepository<Review, Long> {

    /** 지정한 상태의 후기를 생성일 최신순으로 페이징 조회한다. */
    Page<Review> findByPlaceIdAndStatusOrderByCreatedAtDesc(Long placeId, ReviewStatus status, Pageable pageable);

    /** [별점 평균, 전체 건수]. AVG 는 별점 null(제보만 후기)을 알아서 뺀다 */
    @Query("select avg(r.rating), count(r) from Review r where r.place.id = :placeId and r.status = 'ACTIVE'")
    Object[] summarize(@Param("placeId") Long placeId);
}
