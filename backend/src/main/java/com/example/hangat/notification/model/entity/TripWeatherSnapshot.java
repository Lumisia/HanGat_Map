package com.example.hangat.notification.model.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 권역별 최신 알림용 강수 예보.
 * payloadJson은 UTC 예보 시각 → 강수 형태의 JSON이다.
 */
@Entity
@Table(name = "trip_weather_snapshots")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TripWeatherSnapshot {

    @Id
    @Column(name = "region_id")
    private Short regionId;

    @Column(name = "issued_at", nullable = false, columnDefinition = "datetime(6)")
    private LocalDateTime issuedAt;

    @Column(name = "fetched_at", nullable = false, columnDefinition = "datetime(6)")
    private LocalDateTime fetchedAt;

    @Lob
    @Column(name = "payload_json", nullable = false, columnDefinition = "longtext")
    private String payloadJson;

    public TripWeatherSnapshot(
            Short regionId,
            LocalDateTime issuedAt,
            LocalDateTime fetchedAt,
            String payloadJson
    ) {
        this.regionId = regionId;
        replace(issuedAt, fetchedAt, payloadJson);
    }

    /** 오래된 요청이 뒤늦게 끝나도 최신 발표분을 덮지 않는다. */
    public void replace(
            LocalDateTime issuedAt,
            LocalDateTime fetchedAt,
            String payloadJson
    ) {
        if (this.issuedAt != null && issuedAt.isBefore(this.issuedAt)) {
            return;
        }

        this.issuedAt = issuedAt;
        this.fetchedAt = fetchedAt;
        this.payloadJson = payloadJson;
    }
}
