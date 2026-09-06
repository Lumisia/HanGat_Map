package com.example.hangat.course.model.entity;

import com.example.hangat.course.model.enums.CourseStatus;
import com.example.hangat.course.model.enums.CourseType;
import com.example.hangat.course.model.enums.GenerationReason;
import com.example.hangat.course.model.enums.Transport;
import com.example.hangat.map.model.entity.PlaceSourceMapping;
import com.example.hangat.user.model.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
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
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * AI·샘플 코스 - 테이블 명세서 21.0
 *
 * <p>코스 추천(#코스 추천)·스왑(#과밀지역 우회)·저장 코스(MY_001)가 전부 이 행 위에서 돈다.
 * 생성 엔진(한별)·스왑/저장/메인(동현)이 공유하는 테이블이므로 컬럼 추가·의미 변경은 반드시 합의 후에.
 *
 * <p>명세서 CHECK는 DB에 재현하지 않는다(§10-④ 관례). 대신 상태 전이를 이름 있는 메서드로만 열어
 * CHECK가 요구하는 부속 값이 함께 채워지게 한다 - {@link #markSaved(Long, String)} 참고.
 *
 * <p>개정판 명세서 기준: parent_course_id·guest_access_hash·expires_at은 <b>삭제됐다</b> - 되살리지 말 것.
 */
@Entity
@Table(
        name = "courses",
        indexes = {
                // 저장 코스 목록(MY_001): where user_id=? and status='SAVED' order by saved_at desc
                @Index(name = "idx_courses_user_status_saved", columnList = "user_id, status, saved_at"),
                // 메인 샘플 조회: where course_type='SAMPLE' and status='READY'
                @Index(name = "idx_courses_type_status", columnList = "course_type, status"),
                @Index(
                        name = "idx_courses_accommodation_source_mapping",
                        columnList = "accommodation_source_mapping_id")
        }
)
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Course {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * 소유 회원. 비회원 임시 코스·샘플 코스는 NULL(명세서).
     * 소유자 행이 물리 삭제되면 코스는 남고 소유자만 비워진다(ON DELETE SET NULL) -
     * 단 탈퇴는 논리 처리(status=WITHDRAWN)라 이 FK 동작은 최후 안전망이다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", foreignKey = @ForeignKey(name = "fk_courses_user"))
    @OnDelete(action = OnDeleteAction.SET_NULL)   // 명세서: ON DELETE SET NULL
    private User user;

    /** 샘플 코스의 출처 프리셋. USER 코스는 NULL. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "preset_id", foreignKey = @ForeignKey(name = "fk_courses_preset"))
    @OnDelete(action = OnDeleteAction.SET_NULL)   // 명세서: ON DELETE SET NULL - 프리셋이 내려가도 코스는 남는다
    private CoursePreset preset;

    /** 사용자가 선택한 숙소의 외부 출처 identity. 기존 코스와 미선택 코스는 NULL. */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(
            name = "accommodation_source_mapping_id",
            nullable = true,
            foreignKey = @ForeignKey(name = "fk_courses_accommodation_source_mapping"))
    private PlaceSourceMapping accommodationSourceMapping;

    /** SAMPLE이면 preset·title 필수, userId는 NULL(명세서 CHECK - 앱 검증). */
    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "course_type", length = 20, nullable = false)
    private CourseType courseType = CourseType.USER;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "generation_reason", length = 20, nullable = false)
    private GenerationReason generationReason = GenerationReason.INITIAL;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "status", length = 20, nullable = false)
    private CourseStatus status = CourseStatus.GENERATING;

    /** 생성 직후엔 NULL일 수 있다. 저장(SAVED)·샘플(SAMPLE)에는 필수(명세서 CHECK). */
    @Column(name = "title", length = 100)
    private String title;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    /** start_date ≤ end_date는 앱 검증. 일수는 (end - start + 1)로 파생 - 별도 컬럼을 만들지 않는다. */
    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    /** 명세서 SMALLINT UNSIGNED·1~100 - Short 유지 사유는 {@link com.example.hangat.map.model.entity.Region#getId()} 참고. */
    @Builder.Default
    @Column(name = "people", nullable = false)
    private Short people = 1;

    /** 총 예산(원). 조건 미입력이면 NULL - 0원과 구분해야 하므로 기본값을 두지 않는다. */
    @Column(name = "budget_total")
    private Integer budgetTotal;

    @Enumerated(EnumType.STRING)
    @Column(name = "transport", length = 20, nullable = false)
    private Transport transport;

    /** 동일 조건 재생성 판별·요청 추적용 SHA-256 지문. 생성 엔진이 채운다. */
    @Column(name = "input_fingerprint", columnDefinition = "CHAR(64)")
    private String inputFingerprint;

    /** 추천 근거 재현용 - 심사 Q&A에서 "이 코스가 왜 이렇게 나왔나"에 답하는 값. */
    @Column(name = "algorithm_version", length = 30)
    private String algorithmVersion;

    /**
     * course_item_costs 합산 캐시. 원본은 항상 비용 테이블이다 - 스왑 등으로 일정이 바뀌면
     * {@link #updateAggregates}로 같은 트랜잭션에서 다시 채워야 한다.
     */
    @Column(name = "estimated_cost_min")
    private Integer estimatedCostMin;

    @Column(name = "estimated_cost_max")
    private Integer estimatedCostMax;

    /** 코스 카드 표시용 집계 캐시(0~100). 예보 없는 일정만 있으면 NULL - 0으로 채우지 않는다(정직성). */
    @Column(name = "average_congestion_rate", precision = 5, scale = 2)
    private BigDecimal averageCongestionRate;

    @Column(name = "generation_error_code", length = 50)
    private String generationErrorCode;

    @Column(name = "generation_completed_at")
    private LocalDateTime generationCompletedAt;

    @Column(name = "saved_at")
    private LocalDateTime savedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** JPA 전용. */
    protected Course() {
        this.courseType = CourseType.USER;
        this.generationReason = GenerationReason.INITIAL;
        this.status = CourseStatus.GENERATING;
        this.people = 1;
    }

    /** 생성 성공 - READY 전환과 완료 시각 기록을 한 번에. */
    public void markReady() {
        rejectIfDeleted("생성 완료 처리");
        this.status = CourseStatus.READY;
        this.generationCompletedAt = LocalDateTime.now();
    }

    /** 생성 실패 - 오류 코드는 화면 재시도 안내에 쓰이므로 반드시 함께 남긴다. */
    public void markFailed(String errorCode) {
        rejectIfDeleted("생성 실패 처리");
        this.status = CourseStatus.FAILED;
        this.generationErrorCode = errorCode;
        this.generationCompletedAt = LocalDateTime.now();
    }

    /**
     * 비동기 생성 완료가 삭제 뒤에 도착하는 경합 방어 - 삭제된 코스가 READY로 부활하면
     * deleted_at만 남아 명세서 CHECK 짝(DELETED ↔ deleted_at)이 깨진다.
     */
    private void rejectIfDeleted(String action) {
        if (this.status == CourseStatus.DELETED) {
            throw new IllegalStateException("삭제된 코스는 " + action + "를 할 수 없다(id=" + id + ")");
        }
    }

    /**
     * 회원 저장(MY_001). 명세서 CHECK(SAVED면 user_id·title·saved_at 필수)를 이 메서드가 한 번에 채운다.
     * 비회원이 생성한 임시 코스를 로그인 후 저장하는 흐름이라 소유자는 저장 시점에 확정된다.
     *
     * <p>DB CHECK를 재현하지 않는 관례상 <b>이 가드가 마지막 방어선이다</b> - 소유자 없는 SAVED 행은
     * {@code findByUserIdAndStatus}로 영영 조회되지 않는 유령이 된다. 그래서 <b>READY에서만</b> 허용한다:
     * GENERATING·FAILED는 저장할 결과가 없고, 이미 SAVED면 재호출로 소유자가 조용히 바뀌는 사고,
     * DELETED면 deleted_at이 남은 부활이 된다. SAMPLE은 공유 자산이라 특정 회원 소유로 바꿀 수 없다
     * (명세서 CHECK: SAMPLE이면 user_id NULL) - 샘플을 내 코스로 담는 기능은 복제 생성으로 풀 것.
     * 사용자 안내용 검증(로그인 유도)은 서비스에서 먼저 하고, 여기 도달한 위반은 프로그래밍 오류다.
     */
    public void markSaved(User owner, String title) {
        if (owner == null) {
            throw new IllegalArgumentException("저장은 소유 회원이 확정돼야 한다 - owner null");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("저장 코스는 제목이 필수다(명세서 CHECK)");
        }
        if (this.courseType == CourseType.SAMPLE) {
            throw new IllegalStateException("샘플 코스는 회원 소유로 저장할 수 없다 - 복제 생성으로 처리할 것");
        }
        if (this.status != CourseStatus.READY) {
            throw new IllegalStateException("저장은 READY 코스만 가능하다 - 현재 " + this.status);
        }
        this.user = owner;
        this.title = title;
        this.status = CourseStatus.SAVED;
        this.savedAt = LocalDateTime.now();
    }

    /** 마이페이지 코스명 수정. 길이·공백 검증은 요청 DTO에서. */
    public void rename(String title) {
        this.title = title;
    }

    /** 숙소 변경은 코스 일정이나 장소 매핑을 건드리지 않고 FK만 교체한다. */
    public void changeAccommodation(PlaceSourceMapping accommodationSourceMapping) {
        this.accommodationSourceMapping = accommodationSourceMapping;
    }

    /** 논리 삭제(MY_002). deleted_at과 상태를 함께 - 명세서 CHECK가 짝을 요구한다. 멱등 호출 허용. */
    public void softDelete() {
        if (this.status == CourseStatus.DELETED) {
            return;
        }
        this.status = CourseStatus.DELETED;
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * 일정 변경(스왑 등) 후 집계 캐시 재계산 결과를 반영한다 - 비용 테이블과 같은 트랜잭션에서 호출할 것.
     * <b>세 값을 통째로 덮어쓴다</b>(부분 갱신 아님) - 비용만 바뀌었어도 혼잡 평균까지 다시 계산해 넘겨야 한다.
     * 나눠 받으면 "비용만 갱신하며 혼잡에 null"을 넘겨 캐시가 조용히 지워지는 사고가 생긴다.
     */
    public void updateAggregates(Integer estimatedCostMin, Integer estimatedCostMax,
                                 BigDecimal averageCongestionRate) {
        this.estimatedCostMin = estimatedCostMin;
        this.estimatedCostMax = estimatedCostMax;
        this.averageCongestionRate = averageCongestionRate;
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
