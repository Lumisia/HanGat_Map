package com.example.hangat.map.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 여행 권역(동부/서부/남부/북부) - 테이블 명세서 5.0
 *
 * <p>BaseEntity를 상속하지 않는다: 공통 BaseEntity의 컬럼명은 create_date/update_date인데
 * 명세서는 created_at/updated_at이다(설계서 §10-①). ddl-auto:update라 상속하면 명세서에 없는 컬럼이 생긴다.
 * 충돌이 정리되면 아래 두 필드와 콜백만 걷어내고 상속으로 바꾸면 된다.
 */
@Entity
@Table(
        name = "regions",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_regions_code", columnNames = "code"),
                @UniqueConstraint(name = "uk_regions_name", columnNames = "name"),
                @UniqueConstraint(name = "uk_regions_display_order", columnNames = "display_order")
        }
)
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Region {

    /**
     * 명세서는 SMALLINT UNSIGNED. <b>Short를 유지할 것</b> -
     * places.region_id의 컬럼 타입은 Place가 아니라 이 필드가 결정한다.
     * Integer/Long으로 올리면 places.region_id가 int/bigint로 생성되는데 컴파일·부팅 모두 통과해 경고가 없다.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Short id;

    /** EAST / WEST / SOUTH / NORTH 등. 값 목록이 열려 있어 자바 enum이 아닌 문자열로 둔다. */
    @Column(name = "code", length = 20, nullable = false)
    private String code;

    /** 화면 표시명. 프론트 장소 응답의 region 값으로 내려간다. */
    @Column(name = "name", length = 30, nullable = false)
    private String name;

    /** 권역 선택 시 지도 자동 이동 기준(MAP_001). DECIMAL(10,7)이라 double이 아닌 BigDecimal. */
    @Column(name = "center_lat", precision = 10, scale = 7)
    private BigDecimal centerLat;

    @Column(name = "center_lng", precision = 10, scale = 7)
    private BigDecimal centerLng;

    /** 권역 대표 기상청 단기예보 격자(nx). 날씨는 장소별이 아니라 권역별이다(설계서 §2.3). */
    @Column(name = "kma_grid_x")
    private Short kmaGridX;

    @Column(name = "kma_grid_y")
    private Short kmaGridY;

    /**
     * 명세서는 TINYINT UNSIGNED이므로 Byte.
     * place_categories.display_order는 SMALLINT라 타입이 다르다 - 복사하지 말 것.
     * 명세서에 DEFAULT 1이 있으나 같은 컬럼에 UNIQUE도 걸려 있어 기본값을 쓸 수 있는 행이 1개뿐이다
     * (명세서 모순 의심, 확인 대기) - 기본값을 두지 않고 항상 명시 지정한다.
     */
    @Column(name = "display_order", nullable = false)
    private Byte displayOrder;

    /**
     * columnDefinition 필수: Hibernate 6의 MySQLDialect(MariaDBDialect가 상속)는 BOOLEAN을 bit으로 만든다.
     * 그대로 두면 dev는 bit(1), 테스트(H2)는 boolean이 되어 두 환경 스키마가 갈린다.
     * ddl-auto:update는 기존 컬럼 타입을 바꾸지 않으므로 최초 생성 전에 지정해야 한다.
     */
    @Builder.Default
    @Column(name = "is_active", nullable = false, columnDefinition = "BOOLEAN")
    private boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * 기상청 단기예보 격자를 채운다. 마스터 초기화가 <b>격자가 비어 있는 행에만</b> 부른다 -
     * 운영처럼 권역이 먼저 들어간 DB에 격자를 뒤늦게 보태는 경로다.
     */
    public void assignKmaGrid(short gridX, short gridY) {
        this.kmaGridX = gridX;
        this.kmaGridY = gridY;
    }

    /** JPA 전용. 팀 컨벤션이 허용한 Lombok에 @NoArgsConstructor가 없어 직접 선언한다. */
    protected Region() {
        this.isActive = true;
    }

    /**
     * DB DEFAULT는 생성되지 않으므로 이 콜백이 시각을 채운다.
     * JPA 경로에서만 동작하니 data.sql 원시 INSERT는 created_at/updated_at을 직접 명시해야 한다.
     */
    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
