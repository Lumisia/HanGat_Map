package com.example.hangat.notification.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** V8 알림 테이블 매핑. DATETIME 값은 UTC 기준이며 생성은 중복 방지 저장소가 담당한다. */
@Entity
@Table(
        name = "notifications",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_notification_dedupe", columnNames = {"user_id", "dedupe_key"}
        ),
        indexes = {
                @Index(name = "idx_notification_user_id", columnList = "user_id,id"),
                @Index(name = "idx_notification_user_unread", columnList = "user_id,read_at")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 50)
    private String type;

    @Column(nullable = false, length = 150)
    private String title;

    @Column(nullable = false, length = 2000)
    private String message;

    @Column(name = "target_type", length = 40)
    private String targetType;

    @Column(name = "target_id", length = 100)
    private String targetId;

    @Column(name = "dedupe_key", nullable = false, length = 190)
    private String dedupeKey;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime createdAt;

    @Column(name = "read_at", columnDefinition = "DATETIME(6)")
    private LocalDateTime readAt;

    /** 사용자 알림함에서 삭제한 시각. 원래 이벤트의 중복 방지 키는 유지한다. */
    @Column(name = "deleted_at", columnDefinition = "DATETIME(6)")
    private LocalDateTime deletedAt;

    /** 헤더에서만 숨김. 마이페이지 알림 내역은 보존한다. */
    @Column(name = "header_hidden_at", columnDefinition = "DATETIME(6)")
    private LocalDateTime headerHiddenAt;
}
