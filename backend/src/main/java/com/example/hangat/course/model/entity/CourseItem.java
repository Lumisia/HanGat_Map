package com.example.hangat.course.model.entity;

import com.example.hangat.course.model.enums.CourseItemSource;
import com.example.hangat.domain.weather.model.entity.WeatherForecast;
import com.example.hangat.map.model.entity.CongestionForecast;
import com.example.hangat.map.model.entity.Place;
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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 코스 방문 일정 - 테이블 명세서 25.0
 *
 * <p>코스 화면의 한 칸(①②… 마커 하나)이자 <b>스왑이 일어나는 단위</b>다.
 * 스왑은 행 삭제·삽입이 아니라 {@link #replaceWith}로 이 행을 제자리 수정한다 -
 * (day_no, position)이 유지되고 교체 전 장소가 흔적으로 남는다.
 *
 * <p>⚠️ start_time/end_time은 <b>동선 계획 시각이지 혼잡 예측 시각이 아니다.</b>
 * 혼잡 예보는 날짜 단위뿐이므로(정직성 원칙) 시각과 혼잡을 엮는 문구·계산을 만들지 말 것.
 * 개정판 명세서 기준 둘 다 NULL 허용 - 시각 배치가 없는 코스도 성립한다.
 * is_fixed 컬럼은 삭제됐다 - 고정 여부는 {@code itemSource=USER_FIXED}로 판별한다.
 */
@Entity
@Table(
        name = "course_items",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_course_items_day_position",
                columnNames = {"course_id", "day_no", "position"}),
        indexes = @Index(name = "idx_course_items_course_visit", columnList = "course_id, visit_date")
)
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class CourseItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_course_items_course"))
    @OnDelete(action = OnDeleteAction.CASCADE)   // 명세서: ON DELETE CASCADE
    private Course course;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "place_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_course_items_place"))
    private Place place;

    /** 1일차=1. 코스 기간과의 일치는 앱 검증(명세서 상세설명). */
    @Column(name = "day_no", nullable = false)
    private Short dayNo;

    /** 일차 내 순서(1부터) - 지도 번호 마커와 일치. UNIQUE(course_id, day_no, position). */
    @Column(name = "position", nullable = false)
    private Short position;

    @Column(name = "visit_date", nullable = false)
    private LocalDate visitDate;

    @Column(name = "start_time")
    private LocalTime startTime;

    /** start < end는 앱 검증. 자정 넘김 일정은 다음 날짜 항목으로 분리(명세서). */
    @Column(name = "end_time")
    private LocalTime endTime;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "item_source", length = 20, nullable = false)
    private CourseItemSource itemSource = CourseItemSource.AI_RECOMMENDED;

    /** 이전 장소에서 거리(m). 첫 항목은 NULL(명세서) - 0으로 채우지 않는다. */
    @Column(name = "inbound_distance_m")
    private Integer inboundDistanceM;

    @Column(name = "inbound_travel_minutes")
    private Short inboundTravelMinutes;

    /**
     * 저장 시점 혼잡 예보 스냅숏. 예보는 발표 버전별로 쌓이므로({@link CongestionForecast})
     * "그때 뭘 보고 골랐는지"를 행으로 고정할 수 있다 - 재열람 시 최신 예보와 병기한다.
     * 예보 없는 장소는 NULL('혼잡 정보 없음').
     *
     * <p><b>ON DELETE SET NULL이 필수다(명세서).</b> 혼잡 적재 배치는 같은 발표 버전을
     * 재실행할 때 그 버전을 벌크 DELETE 후 다시 넣는다({@code CongestionForecastRepository#deleteVersion}) -
     * 기본 RESTRICT면 스냅숏 하나 때문에 적재 전체가 FK 위반으로 실패한다.
     * 지워지면 스냅숏은 NULL = '혼잡 정보 없음'으로 정직하게 돌아간다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "planned_congestion_forecast_id",
            foreignKey = @ForeignKey(name = "fk_course_items_planned_congestion"))
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private CongestionForecast plannedCongestionForecast;

    /**
     * 저장 시점 날씨 예보 스냅숏(권역 DAILY 행, {@link WeatherForecast}). 혼잡 스냅숏과 같은 이유로
     * ON DELETE SET NULL - 날씨 적재가 같은 발표 버전을 지우고 다시 넣을 때 코스가 FK로 막으면 안 되고,
     * 지워지면 '날씨 정보 없음'으로 정직하게 돌아간다. 예보가 없던 날·권역은 NULL.
     * (V1까지는 참조 테이블이 없어 Long이었다 - Flyway V3에서 FK 전환.)
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "planned_weather_forecast_id",
            foreignKey = @ForeignKey(name = "fk_course_items_planned_weather"))
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private WeatherForecast plannedWeatherForecast;

    /** 혼잡·취향·가격·동선 합성 점수. 엔진 산출값 - 여기서 재계산하지 않는다. */
    @Column(name = "recommendation_score", precision = 8, scale = 4)
    private BigDecimal recommendationScore;

    /** CONGESTION / STYLE / GOOD_PRICE / HIDDEN_GEM / ROUTE. 값 목록이 열려 있어 enum이 아닌 문자열. */
    @Column(name = "recommendation_reason_code", length = 30)
    private String recommendationReasonCode;

    /** 카드 한 줄 근거 문구. 예보가 갱신돼도 "왜 이 장소였는지"를 유지하려고 저장한다. */
    @Column(name = "recommendation_reason", length = 300)
    private String recommendationReason;

    /** 스왑 교체 전 장소(최근 1회). 되돌리기·"어디서 바꿨는지" 표시의 근거. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "replaced_from_place_id",
            foreignKey = @ForeignKey(name = "fk_course_items_replaced_place"))
    @OnDelete(action = OnDeleteAction.SET_NULL)   // 명세서: ON DELETE SET NULL - 흔적이라 장소가 사라져도 일정은 살아야 한다
    private Place replacedFromPlace;

    @Column(name = "memo", length = 300)
    private String memo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** JPA 전용. */
    protected CourseItem() {
        this.itemSource = CourseItemSource.AI_RECOMMENDED;
    }

    /**
     * 스왑(#과밀지역 우회)의 히어로 모먼트 - 이 일정을 대안 장소로 제자리 교체한다.
     *
     * <p>순서(day_no, position)는 그대로 두고 장소·근거·스냅숏만 바꾼다. 교체 전 장소는
     * {@code replacedFromPlace}에, 사용자가 바꿨다는 사실은 {@code itemSource=REPLACEMENT}에 남는다.
     *
     * <p>이전 장소 기준이라 낡는 값(엔진 점수·날씨 스냅숏·이 행의 inbound)은 전부 비운다 -
     * 남기면 교체 전 장소의 값이 새 장소 것처럼 보인다. 단 <b>다음 항목의 inbound도 낡는다</b>는 건
     * 이 행이 알 수 없으므로, 호출부(스왑 서비스)가 다음 항목 재계산과
     * {@link Course#updateAggregates}까지 한 트랜잭션으로 묶어야 한다.
     */
    public void replaceWith(Place newPlace, CongestionForecast plannedForecast,
                            String reasonCode, String reason) {
        this.replacedFromPlace = this.place;
        this.place = newPlace;
        this.itemSource = CourseItemSource.REPLACEMENT;
        this.plannedCongestionForecast = plannedForecast;
        this.plannedWeatherForecast = null;
        this.recommendationScore = null;
        this.inboundDistanceM = null;
        this.inboundTravelMinutes = null;
        this.recommendationReasonCode = reasonCode;
        this.recommendationReason = reason;
    }

    /**
     * 직전 일정에서의 이동 정보 갱신 - 스왑으로 앞뒤 장소가 바뀌면 다시 계산해 넣는다.
     * 첫 슬롯은 null을 넣어 "이동 없음"을 유지한다(0km와 구분).
     */
    public void updateInbound(Integer distanceM, Short minutes) {
        this.inboundDistanceM = distanceM;
        this.inboundTravelMinutes = minutes;
    }

    /** 마이페이지 메모. */
    public void updateMemo(String memo) {
        this.memo = memo;
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
