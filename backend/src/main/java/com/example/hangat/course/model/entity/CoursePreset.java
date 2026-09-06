package com.example.hangat.course.model.entity;

import com.example.hangat.course.model.enums.Transport;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import org.hibernate.annotations.Check;
import org.hibernate.annotations.DialectOverride;
import org.hibernate.dialect.MariaDBDialect;

import java.time.LocalDateTime;

/**
 * 메인 샘플 코스 프리셋 - 테이블 명세서 19.0
 *
 * <p>메인 화면 추천 코스 카드(MAIN_002)의 "메뉴판"이다. 야간 배치가 프리셋마다 SAMPLE 코스를
 * 사전 생성해 두고, 메인은 프리셋별 최신 READY 코스를 내보낸다 - 시연에서 LLM 라이브 호출 금지
 * 원칙(사전 생성 캐시 재생)이 이 테이블에서 시작된다.
 *
 * <p>명세서 20.0 course_preset_publications(공개 포인터)는 <b>보류</b>로 확정됐다(명세서 개정판) -
 * 공개 코스 선택은 포인터 없이 "프리셋별 최신 READY SAMPLE" 조회로 대신한다.
 *
 * <p>BaseEntity 미상속 사유는 {@link com.example.hangat.map.model.entity.Region},
 * UNSIGNED/CHECK 미재현 사유는 {@link com.example.hangat.map.model.entity.Place} 참고.
 */
@Entity
@Table(
        name = "course_presets",
        uniqueConstraints = @UniqueConstraint(name = "uk_course_presets_code", columnNames = "code")
)
@Check(
        name = "ck_course_presets_filter_json",
        constraints = "filter_json IS NULL OR filter_json IS JSON"
)
@DialectOverride.Check(
        dialect = MariaDBDialect.class,
        override = @Check(
                name = "ck_course_presets_filter_json",
                constraints = "filter_json IS NULL OR JSON_VALID(filter_json)"
        )
)
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class CoursePreset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 배치·운영이 쓰는 안정 식별자(예: EAST_2DAYS). id와 달리 환경을 옮겨도 변하지 않는다. */
    @Column(name = "code", length = 30, nullable = false)
    private String code;

    /** 관리용 이름(예: 애월 1박2일). */
    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Column(name = "description", length = 300)
    private String description;

    /** 이 프리셋으로 만든 샘플 코스의 title이 된다 - SAMPLE 코스는 title 필수(21.0 CHECK). */
    @Column(name = "default_title", length = 100, nullable = false)
    private String defaultTitle;

    @Column(name = "duration_days", nullable = false)
    private Short durationDays;

    @Builder.Default
    @Column(name = "default_people", nullable = false)
    private Short defaultPeople = 2;

    @Column(name = "default_budget_total")
    private Integer defaultBudgetTotal;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_transport", length = 20)
    private Transport defaultTransport;

    /**
     * 권역·스타일 등 프리셋 설정(JSON). 명세서 상세설명대로 <b>검색 조건에는 쓰지 않는다</b> -
     * 배치가 생성 요청을 만들 때만 읽으므로 스키마를 굳히지 않고 JSON으로 둔다.
     */
    @Column(name = "filter_json", columnDefinition = "LONGTEXT")
    private String filterJson;

    /** 배치 대상 여부. 내리고 싶은 프리셋은 행 삭제가 아니라 비활성화한다. */
    @Builder.Default
    @Column(name = "is_active", nullable = false, columnDefinition = "BOOLEAN")
    private boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** JPA 전용. */
    protected CoursePreset() {
        this.defaultPeople = 2;
        this.isActive = true;
    }

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
