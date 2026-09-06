package com.example.hangat.review.repository;

import com.example.hangat.review.model.ReviewImage;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 후기 사진 DB 조회 - 키의 사용 여부와 사진이 연결된 후기를 확인한다.
 * 사진 조회 권한 검사와 목록의 일괄 사진 조회에 사용한다.
 */
public interface ReviewImageRepository extends JpaRepository<ReviewImage, Long> {

    // ────────────────────────── 저장 키 및 공개 여부 조회 ──────────────────────────

    /** 이미 후기에 첨부된 키의 재사용을 거절하기 위한 사전 검사다. */
    boolean existsByStorageKey(String storageKey);

    /** 사진과 후기를 함께 읽어 조회 권한 검사 중 지연 로딩이 발생하지 않게 한다. */
    @EntityGraph(attributePaths = "review")
    Optional<ReviewImage> findByStorageKey(String storageKey);

    // ────────────────────────── 후기별 사진 조회 ──────────────────────────

    /** 한 후기의 사진을 표시 순서대로 조회하며 삭제 대상 수집에도 사용한다. */
    List<ReviewImage> findByReviewIdOrderBySortOrder(Long reviewId);

    /** 목록 페이지의 여러 후기 사진을 한 번에 읽어 후기마다 반복 조회하지 않는다. */
    List<ReviewImage> findByReviewIdInOrderBySortOrder(List<Long> reviewIds);
}
