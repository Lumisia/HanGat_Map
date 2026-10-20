package com.example.hangat.notification.service.trip;

import com.example.hangat.course.model.enums.CourseStatus;
import com.example.hangat.course.repository.CourseRepository;
import com.example.hangat.notification.model.TripNotificationModels.ConfirmRequest;
import com.example.hangat.notification.model.TripNotificationModels.Preferences;
import com.example.hangat.notification.model.TripNotificationModels.State;
import com.example.hangat.notification.model.TripNotificationModels.Trip;
import com.example.hangat.notification.model.entity.TripNotificationSettings;
import com.example.hangat.notification.repository.trip.TripNotificationLockRepository;
import com.example.hangat.notification.repository.trip.TripNotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * 여행 확정 / 확정 취소 / 알림 수신 정책.
 * DB 조회와 잠금은 Repository가 담당하고, 이 서비스는 정책과 트랜잭션을 담당한다.
 */
@Service
@RequiredArgsConstructor
public class TripNotificationService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final TripNotificationRepository settings;
    private final TripNotificationLockRepository locks;
    private final TripNotificationCheckpointService checkpoints;
    private final CourseRepository courses;

    // 클래스명과 별개인 기존 운영 설정 키를 유지한다.
    @Value("${hangat.trip-alerts.enabled:false}")
    private boolean enabled;

    // ────────────────────────── 수신 설정 ──────────────────────────

    @Transactional(readOnly = true)
    public Preferences getPreferences(Long userId) {
        return readState(userId).preferences();
    }

    /** 최신 행을 잠근 뒤 화면의 버전을 비교하고 JPA 변경 감지로 저장한다. */
    @Transactional
    public Preferences savePreferences(Long userId, Preferences request) {
        TripNotificationSettings row = locks.lock(userId);
        requireVersion(request.version(), row.getPreferencesVersion());
        row.changePreferences(
                request.aiCourse(), request.forecastChange(),
                request.congestion(), request.tripSummary(), request.reviewRequest(), nowUtc()
        );
        return toState(row).preferences();
    }

    // ────────────────────────── 여행 확정 ──────────────────────────

    @Transactional(readOnly = true)
    public Trip getTrip(Long userId) {
        Trip trip = readState(userId).trip();
        if (trip.courseId() == null) return trip;

        Trip current = findSavedCourse(userId, trip.courseId(), false);
        return new Trip(
                trip.courseId(), trip.courseTitle(), trip.startDate(), trip.endDate(),
                trip.version(), current == null || !sameDates(trip, current)
        );
    }

    /** 본인이 저장한 코스만 확정하며, 기존 여행은 명시적인 확정 요청으로 교체한다. */
    @Transactional
    public Trip confirm(Long userId, ConfirmRequest request) {
        Trip course = findSavedCourse(userId, request.courseId(), true);
        if (course == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "본인이 저장한 코스를 찾을 수 없습니다.");
        }
        if (course.startDate() == null || course.endDate() == null
                || course.endDate().isBefore(course.startDate())
                || course.endDate().isBefore(today())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "여행 날짜를 확인하세요. 이미 끝난 여행은 확정할 수 없습니다."
            );
        }

        // 코스 → 설정 순서로 잠가 알림 생성과의 잠금 순서를 통일한다.
        TripNotificationSettings row = locks.lock(userId);
        Trip previous = toState(row).trip();
        requireVersion(request.expectedVersion(), previous.version());
        if (Objects.equals(previous.courseId(), course.courseId()) && sameDates(previous, course)) {
            return previous;
        }

        row.confirm(
                course.courseId(),
                course.courseTitle(),
                course.startDate(),
                course.endDate(),
                nowUtc()
        );

        checkpoints.reset(row, LocalDateTime.now(KST));

        return toState(row).trip();
    }

    /** 확정 대상만 해제한다. 저장한 코스·기존 알림·수신 설정은 유지한다. */
    @Transactional
    public Trip cancel(Long userId, long expectedVersion) {
        TripNotificationSettings row = locks.lock(userId);
        requireVersion(expectedVersion, row.getTripVersion());
        if (row.getTripCourseId() != null) row.cancel(nowUtc());
        return toState(row).trip();
    }

    // ────────────────────────── 알림 생성 허용 판단 ──────────────────────────

    /** 알림 저장 트랜잭션에서 설정 행 잠금을 유지한 채 수신 여부를 판단한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean allowsGeneral(Long userId, String type) {
        return switch (type) {
            case "SECURITY_LOGIN", "SECURITY_PASSWORD_CHANGED", "NOTICE" -> true;
            case "AI_COURSE_COMPLETED", "AI_COURSE_FAILED" ->
                    !enabled || locks.lock(userId).isAiCourse();
            // 여행 알림은 코스와 확정 버전을 확인하는 경로로만 생성한다.
            default -> false;
        };
    }

    /**
     * 취소·교체·재확정된 여행의 예전 작업은 알림을 만들지 못한다.
     * expectedTripVersion은 외부 조회를 시작하기 전에 확보한 값이어야 한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean allowsTrip(Long userId, Long courseId, long expectedTripVersion, String type) {
        if (!enabled || courseId == null) return false;
        Trip current = findSavedCourse(userId, courseId, true);
        if (current == null) return false;

        State state = toState(locks.lock(userId));
        Trip trip = state.trip();
        Preferences preferences = state.preferences();
        if (!Objects.equals(trip.courseId(), courseId)
                || trip.version() != expectedTripVersion || !sameDates(trip, current)) {
            return false;
        }

        LocalDate date = today();

        boolean beforeTripEnds =
                !date.isAfter(trip.endDate());

        return switch (type) {
            // 출발 전도 허용한다.
            case "FORECAST_CHANGE" ->
                    preferences.forecastChange() && beforeTripEnds;

            case "CONGESTION_WORSENED" ->
                    preferences.congestion() && beforeTripEnds;

            case "TRIP_SUMMARY" ->
                    preferences.tripSummary()
                            && !date.isBefore(trip.startDate().minusDays(1))
                            && !date.isAfter(trip.endDate());

            case "REVIEW_REQUEST" ->
                    preferences.reviewRequest()
                            && date.equals(trip.endDate().plusDays(1));

            default -> false;
        };
    }

    // ────────────────────────── 조회 결과 / 응답 변환 ──────────────────────────

    private State readState(Long userId) {
        return settings.findById(userId).map(this::toState).orElseGet(this::defaultState);
    }

    private Trip findSavedCourse(Long userId, Long courseId, boolean forUpdate) {
        var result = forUpdate
                ? locks.findSavedCourse(userId, courseId)
                : courses.findByIdAndUserIdAndStatus(courseId, userId, CourseStatus.SAVED);
        return result.map(course -> new Trip(
                course.getId(), course.getTitle(), course.getStartDate(), course.getEndDate(), 0, false
        )).orElse(null);
    }

    private State toState(TripNotificationSettings row) {
        Preferences preferences = new Preferences(
                row.isAiCourse(), row.isForecastChange(),
                row.isCongestion(), row.isTripSummary(), row.isReviewRequest(), row.getPreferencesVersion()
        );
        Trip trip = row.getTripCourseId() == null
                ? new Trip(null, null, null, null, row.getTripVersion(), false)
                : new Trip(row.getTripCourseId(), row.getTripTitle(), row.getTripStartDate(),
                        row.getTripEndDate(), row.getTripVersion(), false);
        return new State(preferences, trip);
    }

    private State defaultState() {
        return new State(
                new Preferences(true, true, true, true, true, 0L),
                new Trip(null, null, null, null, 0, false)
        );
    }

    private boolean sameDates(Trip first, Trip second) {
        return Objects.equals(first.startDate(), second.startDate())
                && Objects.equals(first.endDate(), second.endDate());
    }

    private void requireVersion(long expected, long actual) {
        if (expected < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "버전 값이 올바르지 않습니다.");
        }
        if (expected != actual) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "다른 요청에서 변경됐습니다. 다시 조회해 주세요.");
        }
    }

    private LocalDate today() {
        return LocalDate.now(KST);
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
