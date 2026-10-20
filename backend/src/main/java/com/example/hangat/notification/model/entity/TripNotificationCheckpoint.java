package com.example.hangat.notification.model.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 확정 여행의 최초 기준값 / 마지막 비교 상태.
 * 회원 설정 행을 잠근 상태에서만 변경한다.
 */
@Entity
@Table(name = "trip_notification_checkpoints")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TripNotificationCheckpoint {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "trip_version", nullable = false)
    private long tripVersion = -1;

    @Column(name = "change_revision", nullable = false)
    private long changeRevision;

    @Lob
    @Column(name = "baseline_json", nullable = false, columnDefinition = "longtext")
    private String baselineJson = "{}";

    @Lob
    @Column(name = "last_json", nullable = false, columnDefinition = "longtext")
    private String lastJson = "{}";

    @Column(name = "updated_at", nullable = false, columnDefinition = "datetime(6)")
    private LocalDateTime updatedAt = LocalDateTime.now(ZoneOffset.UTC);

    public TripNotificationCheckpoint(Long userId) {
        this.userId = userId;
    }

    /** 재확정 시 이전 여행의 비교 상태를 재사용하지 않는다. */
    public void reset(long tripVersion, String snapshotJson) {
        this.tripVersion = tripVersion;
        this.changeRevision = 0;
        this.baselineJson = snapshotJson;
        this.lastJson = snapshotJson;
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    /** 기준값은 그대로 두고 마지막 관측 상태만 변경한다. */
    public void advance(String lastJson, boolean changed) {
        this.lastJson = lastJson;

        if (changed) {
            this.changeRevision++;
        }

        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}