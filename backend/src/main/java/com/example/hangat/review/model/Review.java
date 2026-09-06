package com.example.hangat.review.model;

import com.example.hangat.map.model.entity.Place;
import com.example.hangat.map.model.enums.CongestionLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

/**
 * 방문 후기(명세서 28.0) - MAP-09.
 * 별점·혼잡제보·한줄평 중 별점 또는 제보가 하나는 있어야 한다(검증은 서비스에서).
 * 삭제는 논리 삭제뿐이다 - status=DELETED + deleted_at.
 */
@Entity
@Table(
        name = "reviews",
        indexes = @Index(name = "idx_reviews_place_status", columnList = "place_id, status, created_at")
)
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * 작성자 ID - 삭제 권한 검사의 기준이다.
     * 사용자 엔티티를 직접 로딩하지 않고 닉네임은 목록 조회 시 별도로 일괄 조회한다.
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "place_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Place place;

    /** 별점 1~5. null = 별점 없이 제보만 한 후기 - 평균에서 제외한다 */
    @Column(name = "rating")
    private Byte rating;

    /** 혼잡 제보. 예보와 같은 3단계를 쓴다 */
    @Enumerated(EnumType.STRING)
    @Column(name = "congestion_report", length = 20)
    private CongestionLevel congestionReport;

    /** 한줄평 최대 60자 */
    @Column(name = "content", length = 60)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private ReviewStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    // ────────────────────────── 후기 상태 변경 ──────────────────────────

    /** 논리 삭제 - 물리 DELETE 금지(명세서) */
    public void delete() {
        this.status = ReviewStatus.DELETED;
        this.deletedAt = LocalDateTime.now();
    }

    // ────────────────────────── 저장 시각 관리 ──────────────────────────

    /** 첫 저장 시 생성/수정 시각을 같은 값으로 기록한다. */
    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    /** 후기 상태 등 엔티티 값이 갱신될 때 수정 시각을 기록한다. */
    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
