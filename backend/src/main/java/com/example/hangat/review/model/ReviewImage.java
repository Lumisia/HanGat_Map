package com.example.hangat.review.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

/**
 * 후기 사진 연결 - 사진의 저장 키, 조회 URL과 후기 안에서의 표시 순서를 기록한다.
 * 전체 storage_key는 파일 조회·삭제 기준이며 UNIQUE 제약으로 중복 첨부를 방지한다.
 */
@Entity
@Table(
        name = "review_images",
        uniqueConstraints = @UniqueConstraint(name = "uk_review_images_key", columnNames = "storage_key")
)
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "review_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Review review;

    /** 신규 사진은 reviews/사용자ID/UUID.ext, 기존 사진은 UUID.ext 형태다. */
    @Column(name = "storage_key", length = 200, nullable = false)
    private String storageKey;

    /** 브라우저에 제공하는 조회 URL이며 MinIO 자격증명이나 임시 서명 URL은 저장하지 않는다. */
    @Column(name = "image_url", length = 500, nullable = false)
    private String imageUrl;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // ────────────────────────── 생성 시각 관리 ──────────────────────────

    /** 후기와 사진이 처음 연결된 시각을 기록한다. */
    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
