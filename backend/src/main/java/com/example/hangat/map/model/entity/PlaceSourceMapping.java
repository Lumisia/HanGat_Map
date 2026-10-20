package com.example.hangat.map.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 장소 ↔ 외부 출처 매핑 - 테이블 명세서 10.0
 *
 * <p><b>이 테이블의 존재 이유는 {@code UNIQUE(source_code, source_place_id)} 하나다.</b>
 * "KTO의 contentid 2850913"이 이미 있으면 DB가 두 번째 삽입을 거부하므로,
 * 배치를 몇 번 돌리든 같은 장소가 두 개 생기지 않는다. 중복 방지를 애플리케이션 로직이 아니라
 * DB 제약이 보장한다.
 *
 * <p>부수 효과가 둘 더 있다.
 * <ul>
 *   <li>집중률(tAtsNm)은 좌표도 고유 ID도 주지 않아 이름으로만 조인해야 하는데,
 *       매칭 결과를 {@code source_code='KTO_CNCTR'} 행으로 남겨 재계산을 피한다(설계서 §3.6)</li>
 *   <li>{@code data_hash}로 바뀐 항목만 골라 갱신한다 - 2,147건을 매번 UPDATE하지 않는다</li>
 * </ul>
 *
 * <p>BaseEntity 미상속 사유는 {@link Region} 참고.
 */
@Entity
@Table(
        name = "place_source_mappings",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_place_source_mappings_source",
                columnNames = {"source_code", "source_place_id"})
)
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PlaceSourceMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 한 장소가 여러 출처를 가질 수 있다(KTO 기본정보 + 집중률 매칭 등). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "place_id", nullable = false)
    private Place place;

    /** {@code data_sources.code}. 컬럼 타입은 {@link DataSource#getCode()}의 자바 타입을 따라간다. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_code", nullable = false)
    private DataSource source;

    /** 출처 쪽 식별자. KTO는 {@code contentid}, 집중률은 {@code tAtsNm}(고유 ID가 없어 이름을 쓴다). */
    @Column(name = "source_place_id", length = 100, nullable = false)
    private String sourcePlaceId;

    @Column(name = "source_url", length = 1000)
    private String sourceUrl;

    /** 출처가 알려주는 최종 수정 시각. KTO {@code modifiedtime}(YYYYMMDDHHmmss)을 파싱해 넣는다. */
    @Column(name = "source_updated_at")
    private LocalDateTime sourceUpdatedAt;

    /** 우리가 마지막으로 동기화한 시각. 오래된 것부터 갱신할 때 정렬 키로 쓴다. */
    @Column(name = "last_synced_at", nullable = false)
    private LocalDateTime lastSyncedAt;

    /** 원본 응답의 해시. 값이 같으면 UPDATE를 건너뛴다. */
    @Column(name = "data_hash", length = 64)
    private String dataHash;

    /**
     * 원본 응답 원문.
     *
     * <p>명세서는 {@code JSON} 타입이지만 {@code TEXT}로 생성한다 - MariaDB에서 {@code JSON}은
     * 사실상 {@code LONGTEXT}의 별칭이라 저장 동작이 같고, H2(MODE=MariaDB)에서는 별도 타입이라
     * 테스트 스키마 생성이 깨질 위험이 있다(§8.3 - 테스트는 H2로 돈다).
     * 값이 필요할 때 애플리케이션에서 파싱한다.
     */
    @Column(name = "raw_payload", columnDefinition = "TEXT")
    private String rawPayload;

    /** 출처에서 사라진 항목을 지우지 않고 비활성으로 남긴다 - 우리 장소를 참조하는 데이터가 있을 수 있다. */
    @Builder.Default
    @Column(name = "is_active", nullable = false, columnDefinition = "BOOLEAN")
    private boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** JPA 전용. */
    protected PlaceSourceMapping() {
        this.isActive = true;
    }

    /**
     * 동기화 결과를 기록한다. 다음 배치가 {@code dataHash}를 비교해 변경분만 갱신할 수 있게 한다.
     * {@code place}·{@code sourcePlaceId}는 이 매핑의 정체성이라 바꾸지 않는다.
     */
    public void markSynced(String dataHash, String rawPayload, LocalDateTime sourceUpdatedAt) {
        this.dataHash = dataHash;
        this.rawPayload = rawPayload;
        this.sourceUpdatedAt = sourceUpdatedAt;
        this.lastSyncedAt = LocalDateTime.now();
    }

    /** 이번 적재 목록에 없었다 - 행은 남기고 비활성으로. 다음 적재에도 없으면 장소가 CLOSED 된다. */
    public void deactivate() {
        this.isActive = false;
    }

    /** 출처에 다시 나타났다. */
    public void activate() {
        this.isActive = true;
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.lastSyncedAt == null) {
            this.lastSyncedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
